# CobbleAGPI

A Fabric mod for Minecraft that lets AI agents participate in Cobblemon battles by controlling Radical Cobblemon Trainers.

This mod was made as a proof-of-concept for the [Agent Game Playing Interface](https://github.com/JacobBruce/AGPI) and requires the [Loci](https://github.com/JacobBruce/Loci) harness to work.

If you are looking for the latest version of this mod please see the [Modrinth page](https://modrinth.com/mod/cobbleagpi).

## Getting started:

1. Download Loci then run it and create a new house
2. Install the [skill file](https://github.com/JacobBruce/AGPI/cobbleagpi/cobblemon.md) and enable it for your house
3. Download and install this mod into your Minecraft instance
4. Start Minecraft then enable gaming mode in Loci using the game console
5. The agent controls the trainer when a battle starts and talks in chat as them

## Required mods:

- [Cobblemon](https://modrinth.com/mod/cobblemon)
- [Radical Cobblemon Trainers API](https://modrinth.com/mod/rctapi/)
- [Radical Cobblemon Trainers](https://modrinth.com/mod/rctmod/)

## Build

Gradle itself has to run on JDK 25. That install has no `javac`, so the mod is compiled with the JDK 21 toolchain. Install `openjdk-21-jdk` if `javac` is missing from `/usr/lib/jvm/java-21-openjdk-amd64`. Do not use the system `gradle` command (it is far too old), and do not set `GRADLE_USER_HOME`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew build
```

The first run downloads Gradle into `~/.gradle/wrapper/dists`. That is the wrapper's own copy. Later builds reuse it. The jar file is saved to `build/libs/`. Put it in the instance `mods` folder next to Cobblemon, RCT, and rctapi.

## Connect

The listener starts with the Minecraft server on `http://127.0.0.1:24740`, the same default as the Game Console in Loci.

The port and `move_seconds` (how long the agent has to choose before RCT does) are in `config/cobbleagpi.properties`.

Copy `cobblemon.md` into the shared skills folder or the house `skills/` folder before connecting from the Game Console.
