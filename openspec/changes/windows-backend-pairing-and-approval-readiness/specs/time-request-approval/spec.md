# Delta for time-request-approval

## MODIFIED Requirements

### Requirement: Parent issues approve or deny verdict

The system MUST authorize verdicts server-side. For APPROVED decisions, `approve-request` MUST commit the verdict and the grant atomically, so exactly one grant exists for a successful approval. For DENIED decisions, it MUST record the denial without creating a grant. Retries MUST be safe and MUST not duplicate grants.
(Previously: approval created a grant and FCM'd the child, denial only updated the request, and the UI optimistically removed cards.)

#### Scenario: Approved request creates exactly one grant
- GIVEN a pending request owned by the authenticated parent
- WHEN the parent approves it
- THEN the system MUST mark the request approved and MUST create one matching grant in the same committed outcome

#### Scenario: Duplicate approval retry does not duplicate the grant
- GIVEN an approval already committed successfully
- WHEN the same approval is retried
- THEN the system MUST return the already-resolved outcome and MUST NOT create a second grant

#### Scenario: Denied request does not create a grant
- GIVEN a pending request owned by the authenticated parent
- WHEN the parent denies it
- THEN the system MUST record the denial and MUST NOT create any grant

#### Scenario: Unauthorized caller changes nothing
- GIVEN the caller is not the owning parent or lacks a valid session
- WHEN the caller attempts approval or denial
- THEN the system MUST reject the request and MUST leave the request and grants unchanged

#### Scenario: Partial failure leaves no partial approval state
- GIVEN the backend fails before the approval commit completes
- WHEN the request aborts
- THEN the system MUST leave the request unresolved and MUST create no grant
