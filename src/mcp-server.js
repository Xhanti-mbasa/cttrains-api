#!/usr/bin/env node

const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} = require('@modelcontextprotocol/sdk/types.js');

const cttrains = require('./scrapers/cttrains.js');
const cache = require('./utils/cache.js');

const server = new Server(
  {
    name: 'cttrains-mcp',
    version: '1.0.0',
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

/**
 * Register the tools for the AI to discover
 */
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: 'get_train_schedule',
        description: 'Get the Cape Town Metrorail train schedule between two stations on a specific date.',
        inputSchema: {
          type: 'object',
          properties: {
            from: {
              type: 'string',
              description: 'Departure station name (e.g. "Bellville", "Cape Town")',
            },
            to: {
              type: 'string',
              description: 'Arrival station name (e.g. "Cape Town")',
            },
            date: {
              type: 'string',
              description: 'Travel date in YYYY-MM-DD format. Required.',
            },
            time: {
              type: 'string',
              description: 'Departure time in HH:MM format (optional, defaults to "06:00")',
            },
          },
          required: ['from', 'to', 'date'],
        },
      },
      {
        name: 'get_line_timetable',
        description: 'Get the full day timetable for a specific train line.',
        inputSchema: {
          type: 'object',
          properties: {
            line: {
              type: 'string',
              description: 'The train line identifier (southern, northern, central, cape_flats, monte_vista)',
              enum: ['southern', 'northern', 'central', 'cape_flats', 'monte_vista'],
            },
            days: {
              type: 'string',
              description: 'The day type (e.g. "weekday", "saturday", "sunday")',
            },
          },
          required: ['line'],
        },
      },
      {
        name: 'get_service_updates',
        description: 'Get the latest real-time service disruptions, delays, and cancellations for Cape Town Metrorail.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
    ],
  };
});

/**
 * Handle tool execution requests from the AI
 */
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    switch (request.params.name) {
      case 'get_train_schedule': {
        const { from, to, date, time } = request.params.arguments;
        const results = await cttrains.scrapeSchedule({ from, to, date, time });
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(results, null, 2),
            },
          ],
        };
      }

      case 'get_line_timetable': {
        const { line, days } = request.params.arguments;
        const results = await cttrains.scrapeLineTimetable({ line, days: days || 'weekday' });
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(results, null, 2),
            },
          ],
        };
      }

      case 'get_service_updates': {
        // Use cache if possible since this is heavily rate-limited by the site
        const cached = await cache.get('service_updates');
        let updates = cached;
        
        if (!updates) {
          updates = await cttrains.scrapeServiceUpdates();
          await cache.set('service_updates', updates, 5 * 60);
        }
        
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(updates, null, 2),
            },
          ],
        };
      }

      default:
        throw new Error(`Unknown tool: ${request.params.name}`);
    }
  } catch (error) {
    return {
      content: [
        {
          type: 'text',
          text: `Error executing tool ${request.params.name}: ${error.message}`,
        },
      ],
      isError: true,
    };
  }
});

/**
 * Start the MCP server using stdio transport
 */
async function startServer() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // console.error is used instead of console.log because stdio transport uses stdout for protocol messages
  console.error('CTTrains MCP Server running on stdio');
}

startServer().catch((error) => {
  console.error('Fatal error in MCP server:', error);
  process.exit(1);
});
