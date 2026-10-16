/**
 * Events (MASTER_PLAN 6): {@link in.dexter.events.OutboxWriter} writes an
 * {@link in.dexter.events.EventEnvelope} in the business transaction;
 * {@link in.dexter.events.relay.OutboxRelay} publishes it; consumers
 * ({@link in.dexter.events.consumer.EventConsumer}) apply it exactly once through
 * {@link in.dexter.events.InboxGuard} and drop stale versions with
 * {@link in.dexter.events.ProjectionVersionGuard}. Topics: {@link in.dexter.events.Topics},
 * {@link in.dexter.events.topics.TopicRegistry}.
 */
package in.dexter.events;
