require('dotenv').config();
const readline = require('readline');
const { spawn } = require('child_process');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StdioClientTransport } = require('@modelcontextprotocol/sdk/client/stdio.js');

const apiKey = process.env.GEMINI_API_KEY;
if (!apiKey) {
  console.error("❌ GEMINI_API_KEY is not set in your .env file!");
  console.error("Please add it and try again.");
  process.exit(1);
}

const genAI = new GoogleGenerativeAI(apiKey);

// Basic mapping from JSON Schema types to Gemini schema types
function mapMcpSchemaToGeminiType(type) {
  const map = {
    'string': 'STRING',
    'number': 'NUMBER',
    'integer': 'INTEGER',
    'boolean': 'BOOLEAN',
    'array': 'ARRAY',
    'object': 'OBJECT'
  };
  return map[type] || 'STRING';
}

/**
 * Converts MCP tools to Gemini's FunctionDeclarations format
 */
function convertToolsForGemini(mcpTools) {
  return mcpTools.map(tool => {
    const props = {};
    const schemaProps = tool.inputSchema?.properties || {};
    
    for (const [key, value] of Object.entries(schemaProps)) {
      props[key] = {
        type: mapMcpSchemaToGeminiType(value.type),
        description: value.description || '',
      };
      // Gemini enums require an array of strings
      if (value.enum) {
        props[key].enum = value.enum;
      }
    }

    return {
      name: tool.name,
      description: tool.description || '',
      parameters: {
        type: 'OBJECT',
        properties: props,
        required: tool.inputSchema?.required || [],
      }
    };
  });
}

async function start() {
  console.log("Starting MCP Server...");
  
  // 1. Spawn the MCP Server
  const serverProcess = spawn('node', ['src/mcp-server.js'], {
    stdio: ['pipe', 'pipe', 'inherit'] // pipe stdin/stdout, inherit stderr for logs
  });
  
  // 2. Connect the MCP Client over Stdio
  const transport = new StdioClientTransport({
    command: 'node',
    args: ['src/mcp-server.js'],
  });
  
  const mcpClient = new Client({ name: 'gemini-client', version: '1.0.0' }, { capabilities: {} });
  await mcpClient.connect(transport);
  
  // 3. Fetch tools from the MCP Server
  const { tools } = await mcpClient.listTools();
  console.log(`✅ Connected to MCP Server! Loaded ${tools.length} tools.`);
  
  // 4. Configure Gemini with the tools
  const geminiTools = [{
    functionDeclarations: convertToolsForGemini(tools)
  }];
  
  const model = genAI.getGenerativeModel({
    model: "gemini-2.5-flash",
    tools: geminiTools,
    systemInstruction: "You are a helpful assistant for Cape Town Metrorail commuters. Use the tools provided to look up train schedules and updates. Today is " + new Date().toISOString().split('T')[0]
  });
  
  const chat = model.startChat();
  
  // 5. Start interactive CLI
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  console.log('\n🚂 Gemini + CTTrains MCP Client Started!');
  console.log('Ask me about train schedules or type "exit" to quit.\n');

  const askPrompt = () => {
    rl.question('You: ', async (userInput) => {
      if (userInput.toLowerCase() === 'exit') {
        console.log('Goodbye!');
        process.exit(0);
      }

      try {
        let result = await chat.sendMessage(userInput);
        let call = result.response.functionCalls() && result.response.functionCalls()[0];
        
        // Handle tool calls in a loop (in case Gemini wants to use multiple tools)
        while (call) {
          console.log(`\n🤖 Gemini called tool: [${call.name}] with arguments:`, call.args);
          
          try {
            // Execute the tool against our MCP server
            const mcpResponse = await mcpClient.callTool({
              name: call.name,
              arguments: call.args
            });
            
            // Pass the results back to Gemini
            const functionResponse = {
              functionResponse: {
                name: call.name,
                response: mcpResponse,
              }
            };
            
            result = await chat.sendMessage([functionResponse]);
            call = result.response.functionCalls() && result.response.functionCalls()[0];
          } catch (toolErr) {
            console.error(`\n❌ Tool execution failed: ${toolErr.message}`);
            // Tell Gemini it failed
            result = await chat.sendMessage([{
              functionResponse: {
                name: call.name,
                response: { error: toolErr.message },
              }
            }]);
            call = null; // stop looping on failure
          }
        }
        
        console.log(`\nGemini: ${result.response.text()}\n`);
      } catch (err) {
        console.error("\n❌ Error communicating with Gemini:", err.message);
      }
      
      askPrompt();
    });
  };

  askPrompt();
}

start().catch(console.error);
