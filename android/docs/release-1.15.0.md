## CT Trains 1.15.0

- The owner-approved Lab design is now the default CT Trains experience: Now, Plan and Mine.
- Production keeps `za.co.cttrains.app`, the existing signing certificate, Room database, Keystore alias, reminders, widgets and update channel. Lab remains a separate optional testing package.
- Includes the #25 departure-card and large-text walking/fare fixes, and #34 public-first update checks, rate-limit handling and channel isolation.
- Penelope (#35): Now, Depart after and Any time / All day retain the selected route/date/modes. All-day searches show the source response in chronological order, including earlier departures; local Show more reveals additional results without another request. Past trips are labelled and cannot be started or saved as upcoming rides.
- All-day coverage is partial: rail source starts at 03:00 and supplies its own result set; buses check up to six direct routes and three stop pairs per route. No full-network or missing-service guarantee is claimed. Cached data is labelled; unsupported dates continue to respect the existing service rules.
- Preferred train-station controls and saved-place continuation remain available in the promoted planner. Optional integrations keep their separate opt-ins.

### Rollback

Previous production source: `d4b4d7eed5606efb101953b276bcb99cdf760283`; previous signed release: `android-v1.14.0-build.61`. Keep prior releases. For an in-place rollback, rebuild the previous source with the same production key and a versionCode greater than the release being replaced, then pass the release checks. Do not uninstall or clear data; Android version downgrades are not an upgrade path. Room schema and Keystore alias did not change.

### Verification

Production CI runs core tests, debug/release lint and builds, the promoted launcher UI suite, native storage/directions/widget checks, all-day cached transfer/pagination tests, and a baseline-to-current debug upgrade preserving encrypted data. The production publishing step verifies the actual release signature and baseline certificate, package, version and supported Android API. Phone installation is confirmed only after the owner updates their device.
