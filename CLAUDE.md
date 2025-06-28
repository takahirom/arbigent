# Testing

```
./gradlew installDist
./arbigent-cli/build/install/arbigent/bin/arbigent --help
# no need to set --project-file, it is set in the arbigent.properties file(do not read the properties file)
./arbigent-cli/build/install/arbigent/bin/arbigent run --scenario-ids="open-model-page"
```