# Implemented ownership and recovery boundary

This supersedes the Fix003 claims of shared files, host fencing and complete readiness.

The online 1v1 path now has one production `ReliableMatchCoordinator`, hosted on the original host installation, and a `ReliableMatchClient` protocol. UI actions and MQTT requests enter its serialized event loop. The guest submits requests and renders accepted state; it never independently commits a pick, timeout, pause, result or host promotion. The controller installation ID is persisted across process restarts. This is an honest-client, single-original-installation model; it is not broker-enforced protection against malicious publishers or cloned installations.

## Implemented guarantees

- Explicit version-2 lobby capability advertisement and a fresh per-match HELLO/SYNC/application-ACK exchange. Missing versions mean unsupported. Old gameplay packets cannot mutate a new 1v1 match.
- Match identity includes the room and nonzero seed. Immutable participants, host installation, actual turn, accepted revision, snapshot sequence and stable action IDs constrain mutations.
- Host timing uses a monotonic deadline. Pausing freezes the exact remaining milliseconds; resuming consumes that remainder. Only a genuine new turn receives 30,000ms. No timeout backlog or two-second grace is used by the new path.
- UI countdowns are projections of accepted state, not another source of timeout decisions. Remote display samples have network delivery uncertainty; this is not a promise that two screens agree to the millisecond under arbitrary packet delay.
- Complete snapshots contain board layouts/marks, actual pick turn IDs and actors, turn order, remaining time, pause owner, committed host-observed episodes, accepted chat and a canonical final result.
- The actual production MQTT topic is `bingo/v4/match/{room}/{seed}/state`, retained at QoS 1. Controller requests, correlated sync responses and application acknowledgements use the existing room topic. No HTTP per move and no new backend deployment.
- Local candidate persistence precedes publication. Acceptance follows confirmed publication and local acceptance persistence. A failed or ambiguous step blocks further mutations and reconciles broker state through a fresh subscription/barrier. A pending local record is not proof of a shared result.
- Initial and final retained publications in the Android transport additionally require a fresh subscription to return the exact snapshot with the MQTT RETAIN flag. A live self echo or PUBACK alone is insufficient to prove retained storage. Reconnect clears this verification cache.
- A final result is immutable in the accepted sequence. The absent peer can retrieve it after the winner exits. A previously accepted local final result can render offline. Statistics/history and an account-bound result receipt commit atomically, preventing duplicate application after restart; the existing Firestore history uses the stable record ID.
- Account/match checks reject old asynchronous checkpoint writes after a rematch, dismiss or account change. Ownerless legacy data is preserved rather than assigned to another participant. A closed lobby listing does not erase a recoverable 1v1 checkpoint.

## Remaining required behavior: blocked

**The guest cannot continue accepting moves/timeouts or commit a victory claim while the original host is unavailable.** It waits visibly and preserves state. This is a deliberate safety boundary, not fulfillment of the requested uninterrupted guest play. Guest surrender also requires the original controller to confirm a terminal result. Returning to the menu remains available without inventing a result.

A reliable implementation of that missing availability requires a broker-enforced/fenced ownership mechanism or a separate MQTT-connected match authority. Heartbeat silence, local mutexes and ordinary retained last-writer-wins records cannot provide it. The existing EMQX Serverless feature matrix does not offer the exclusive subscription add-on: [EMQX feature comparison](https://docs.emqx.com/en/emqx/latest/get-started/feature-comparison.html), [exclusive subscription semantics](https://docs.emqx.com/en/emqx/latest/get-started/messaging/mqtt-exclusive-subscription.html). No service, broker tier or rules were deployed/changed during this repair.

A future MQTT authority can keep all gameplay on MQTT: accept versioned requests, serialize/persist actions, validate the current episode, publish complete accepted snapshots, and suspend when both participants are absent. It must have one enforceable writer, durable storage and a concrete deployment/credential model; running an arbitrary second publisher is not that mechanism. The current client protocol would need an explicit authority identity/negotiation extension rather than masquerading as a second copy of the host.

## Broker retention and rollout limits

The real broker was tested with isolated verification rooms, separate local stores and separate JVM processes. It retained and replayed the final result after the writer process exited. This does not test broker disaster recovery or operator deletion.

EMQX documents never-expiring retained messages for Serverless, with 2,000 retained-message entries, 1MB maximum payload and 10 subscriptions per client: [retained messages](https://docs.emqx.com/en/cloud/latest/deployments/retain_message.html), [quotas](https://docs.emqx.com/en/cloud/latest/create/restriction.html). The transport releases previous match subscriptions and fails closed if initial/final retained read-back fails. Production still needs quota monitoring and an explicit acknowledged-final archival/cleanup policy before large-scale rollout. No arbitrary short TTL or deletion of an absent player's result was introduced.

The release candidate is not certified ready. Full Android paired-device UI/timer testing and a same-setup physical baseline latency comparison remain outstanding. Two existing AVDs were attempted without KVM; neither produced usable boot-complete screenshots before shutdown. No APK was installed on them and no physical user was invited.
