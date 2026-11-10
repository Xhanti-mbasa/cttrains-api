# CT Trains agent workflow

For each fix, open or reuse a GitHub issue that describes the problem. Verify the fix, then push it directly to the branch the user is working on. Open a pull request only when the user explicitly asks for one. Update the issue with the commit and verification results.

## Private handoff notes

Use the owner-controlled private handoff repository when the owner has granted access. Read its `.llm/README.md` and add a separate `.llm/` entry for each task with the agent and model if known, date, issue, changes, verification, and any open work. Push that entry to the private handoff repository.

The `.llm/` and `.logs/` directories are ignored in this source repository. Keep handoff notes, credentials, and signing material out of source commits and GitHub issues.
