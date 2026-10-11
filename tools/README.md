# tools

One-off probes and one build-critical helper.

## For anyone building this mod

`tools/MergeJars.java` is **not** a probe: `build.gradle` runs it from the
`ensureMergedMinecraftJar` task. Loom 1.18.2 writes an empty (22-byte)
`minecraft-merged.jar` for Minecraft 26.2 without reporting an error, which makes
every `net.minecraft.*` import fail with "package does not exist". That task
rebuilds the jar with this tool and copies it over Loom's stub. Do not delete it.

It is a single-file source launch, so it needs no build step of its own:

```
java tools/MergeJars.java <client.jar> <extracted-server.jar> <merged.jar>
```

## The rest

These were written while working out how 26.2 is put together on this machine.
They are kept because they made the mod's numeric decisions verifiable — every
permission level and "is this node reachable" fact in the code was read out of
the real classes with them. They are not wired into the build.

| Tool | What it answers |
| --- | --- |
| `api-probe.ps1` | `javap` a class out of the 26.2 client jar, with an optional regex filter |
| `ExtractClasses.java` | pull named `.class` files out of a jar for closer reading |
| `ListClasses.java` | find class names in a jar by substring |
| `ListJar.java` | list a jar's entries |
| `FindString.java` | grep a jar's raw bytes for a constant, to prove where a message comes from |
| `MergeProbe.java` | show what Loom's merger produced, before trusting it |
| `Download.java`, `FetchText.java`, `FetchLibs.java`, `GsonProbe.java` | fetch artifacts and text when a build tool could not |
| `CheckTls.java`, `ProbeHosts.java` | diagnose the local TLS-terminating proxy this machine runs |

`api-probe.ps1` expects the client jar at `<repo>/.cache/mc262-client.jar`; point
it elsewhere by editing `$jar`. The Java tools all take their paths as arguments.
