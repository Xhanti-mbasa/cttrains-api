# Claude brief: CT Trains Lab

You own the experimental visual redesign in this same repository. ChatGPT owns developer authorization, release/update controls and the existing edition. Read AGENTS.md and the owner's private handoff instructions first. Open/reuse an issue, keep a task-specific private .llm note with your agent/model, verification and commit, and push directly. No PR unless the owner requests one. Never store credentials or private handoffs in this public repository. Do not overwrite concurrent work; fetch and inspect the branch head before each push.

## Goal
Create a deeply refined, distinct Android commuter experience, preserving CT Trains' purpose and working behavior. This is a real native app, not a landing page or a cosmetic recoloring. You have broad design freedom, including navigation and interaction changes; Material styling is optional. Preserve all underlying transit behavior and consent controls. Work within an isolated UI package such as ui/lab and avoid changing the existing edition's screens. Coordinate any shared engine change in the issue before editing it.

## Design workflow
Use Claude Design to explore and prototype the experience, then implement and verify the native Android version. Start from the commuter's jobs: get home, find a reachable train, understand transfers, save/remind, navigate offline, recover from a missed ride. Inventory every current capability before restructuring it. Research other good transit/mobile products and state your reasoning. The owner offered Juxtopposed and Enrico Tartarotti as inspiration, not requirements to copy. Likely references:
- https://www.youtube.com/@juxtopposed
- https://www.juxtopposed.com/
- https://www.youtube.com/@enricotartarotti
- https://support.claude.com/en/articles/14604416-get-started-with-claude-design

Look at their actual work rather than inventing quotes or a named design philosophy. Use wider research where it improves the result. Keep design sources and original decisions in your task brief. Do not reproduce another app's proprietary assets or confuse branding with an official rail operator.

Build a coherent system for typography, spacing, colors, information density, icons, motion, loading, failure, offline, permission-denied, no-result and first-use states. Make time, origin, destination, walk feasibility, transfers, scheduled ETA and uncertainty immediately readable. Do not present scheduled predictions as live train tracking. Avoid dead space, truncated critical information, generic dashboard tiles and unnecessary nested settings. Give every state a useful next action. Include home-screen widgets in the design system, including compact sizes, large fonts, long station names and empty/stale states.

## Behavior contract
Keep train, MyCiTi and Golden Arrow planning; source/fare details; nearby station and place search; Home/Work and saved places; map visibility and offline maps; saved routes; reminders, recurrence, missed-ride follow-ups, opt-in boarding detection; Smart Cards; trip history and correction; directions; optional community/Matrix sharing; WhatsApp integration; backup/import/export; updates; all privacy controls. Preserve source warnings, permission flows, local data boundaries and timetable-date validity. Reorganizing a flow is allowed; silently removing its capability or changing transit results is not.

## Separate installation
The build already accepts CTTRAINS_EXPERIMENTAL=true and produces applicationId za.co.cttrains.app.experimental, label CT Trains Lab, and BuildConfig.EXPERIMENTAL_EDITION=true. Use that flag at the app UI root to choose your new UI once ready. The regular edition must keep its present identity. A second app has a separate database, preferences, Android Keystore and widget namespace; do not use a shared UID or silently read the other app's data. Use the existing reviewed encrypted backup flow for explicit migration.

Do not publish an unchanged duplicate app as the redesign. There is currently no experimental APK. Publish only after the design and behavior checks pass. Add an explicit experimental workflow and channel (e.g. android-experimental-v...) that cannot be mistaken for a regular app update. Its manifest must identify its own package. Do not weaken the regular updater's package, signer, version, checksum or download-host checks to make lab installation work. The developer screen currently links to experimental releases and explains availability; replace that placeholder with a verified secondary-install flow when a real APK exists. Android installation consent remains mandatory.

## Acceptance
Document the before/after navigation and all capability mappings. Verify accessibility, TalkBack labels/order, contrast, 48dp actions, small screens, 200% fonts, rotation, dark/light appearance, reduced motion, long names and low-memory/offline behavior. Test real reachable journeys and reminders, not only screenshots. Run required core/Android checks and attach real renders/screenshots of all main flows, compact widgets and error states. Keep performance at least as good as the existing edition; avoid heavy image bundles and perpetual animation. Review the resulting experience as a whole before publishing.

Do not embed master codes, invitation lists, GitHub write tokens or server secrets. Use the existing server roles for developer access. Do not add remote maintenance to the polished public edition: retire it at the service and remove its client handling as part of public launch readiness, preserving updates and recovery.
