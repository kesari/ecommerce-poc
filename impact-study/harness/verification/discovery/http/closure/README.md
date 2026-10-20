# H08 controlled cache closure

The public rollout policy is part of this case: **preserve the existing cache
TTL; previous-policy cache entries may remain visible until normal expiration,
and every new nonmetro computation must use the new SLA.** This policy must be
supplied before predictions. No immediate universal adoption or cache invalidation
requirement is implied.

The protected test executes the actual pinned `ShipmentService.estimate` and
`DeliveryEstimator` against a controlled `EstimateCache`. The first read returns
a previous-policy +4/+6 entry unchanged and writes nothing. Manually clearing the
controlled entry models the end of its allowed lifetime. The next read computes
and stores the current policy: +4/+6 at baseline, +5/+7 after the change. A third
read reuses the new entry. Exactly three reads and one write occur; unrelated
repository/outbox/inbox ports have no interactions. Shipping 10000 minor units
and INR currency remain unchanged. The baseline, changed, no-edit validation
(`repaired`) and incompatible fault all compile the actual production sources.

All four phases passed on 2026-10-19. The incompatible fault adds one day to the
requested +5/+7 default and is rejected by `BUSINESS_NEW_COMPUTATION_FROM` while
the warm previous-policy entry remains allowed. Protected assertions are unchanged
across the four phases. Root reviewed criteria and test pins before execution:

- Criteria: `cf432b5bf67b8f0eab77584ff47022b6f1861b603d8da26d76a02aadebc6170c`
- Test: `53d63f4ca3b750401bf2c981e64276018a0a775562975ac022fb667051a3a90a`

This establishes the actual service's warm/cold branch behavior. It does not
execute real cache expiry timing, Valkey, event delivery, databases or deployed
controller wiring. The existing five-minute TTL is a source configuration fact,
not a measured five-minute timer result. No Docker or paid model calls were used.
