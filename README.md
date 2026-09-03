# Jobs Overflow XP

Client-side Fabric mod for Minecraft 1.21.8 that tracks Jobs Reborn XP earned at/after level 200.

## Features

- Tracks overflow XP separately for each Jobs Reborn job.
- Tracking begins when the mod is installed/first run. Existing server XP is not retroactively added.
- Data persists in `config/jobs-overflow.json`.
- Replaces the Jobs Reborn bossbar with a custom display showing:
  - current job level and XP
  - tracked overflow XP
  - current XP gain
- Client commands:
  - `/jobsoverflow`
  - `/jobsoverflow reset`
  - `/jobsoverflow resetjob <job>`

## Build

Use Java 21 and run `./gradlew build` (or `gradlew.bat build` on Windows).

The finished jar will be in `build/libs/`.
