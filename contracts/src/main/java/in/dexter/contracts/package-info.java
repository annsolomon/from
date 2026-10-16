/**
 * Cross-service contracts (MASTER_PLAN 6.1). Each event type has a JSON Schema in {@code
 * contracts/events/<type>.v<n>.json} and a record here named {@code <Action>V<n>}. Evolution is
 * additive; a breaking change is a new version, and both versions are produced for one release.
 * {@code SchemaContractTest} keeps schemas and records in step.
 */
package in.dexter.contracts;
