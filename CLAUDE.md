# Testing

```
./gradlew installDist
./arbigent-cli/build/install/arbigent/bin/arbigent --help
# no need to set --project-file, it is set in the .arbigent/settings.local.yml file
./arbigent-cli/build/install/arbigent/bin/arbigent run --scenario-ids="open-model-page"
```