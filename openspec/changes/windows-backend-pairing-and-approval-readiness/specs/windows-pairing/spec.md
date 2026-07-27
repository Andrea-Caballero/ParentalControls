# Delta for windows-pairing

## ADDED Requirements

### Requirement: Windows pairing codes are parent-owned and single-use

The system MUST bind each pairing code to exactly one authenticated parent account. It MUST reject expired, malformed, or unknown codes, and it MUST allow a code to pair at most once.

#### Scenario: Valid code creates one paired device
- GIVEN an authenticated parent owns an active pairing code
- WHEN a Windows child device redeems that code
- THEN the system MUST create exactly one paired device linked to that parent

#### Scenario: Expired or invalid code is rejected
- GIVEN a code is expired, malformed, or unknown
- WHEN a child device submits it
- THEN the system MUST reject the request and MUST NOT create a paired device

#### Scenario: Reused code is rejected
- GIVEN a code was already redeemed successfully
- WHEN the same code is submitted again
- THEN the system MUST reject the second attempt and MUST NOT create a second device link

### Requirement: Pairing redemption is atomic and retry-safe

The system MUST leave no partial pairing state after a failure. A successful pairing MUST be all-or-nothing: either the device is paired once, or nothing changes.

#### Scenario: Retry after a successful pairing does not duplicate state
- GIVEN a first redemption already committed successfully
- WHEN the client retries the same code after a network timeout
- THEN the system MUST return a non-success outcome and MUST NOT create another paired device

#### Scenario: Partial failure leaves no half-created pairing
- GIVEN the server fails before the pairing commit completes
- WHEN the request aborts
- THEN the system MUST create no paired device and MUST leave the code unconsumed
