---
date: 2026-11-07
tags:
  - project
source: "Team requirement notes"
---
financial ledger and digital wallet API 

functional requirements ( main things that need to implemented for sure according to the project )
1. wallet initialization - every user registers with a unique username (3–20 alphanumeric/underscore chars, case-insensitive unique; internal UUID retained as primary identity) and can create/lookup separate wallets by username and currency (`POST /users/{username}/wallets`, `GET /users/{username}/wallets/{currencyCode}`, plus `GET /wallets/{walletId}`), backed by ledger entries (both credit and debit entries must be supported here)
2. idempotent transfers - have a unique key to each transaction so that the payments do not happen twice just coz of the internet connection lost and the user doesnt know if the payment is successful even if it actually does .The backend remembers that it has already processed this key.If the same request comes again with the same key, the backend should return the original result instead of executing the transfer again... ie the money should be moved only once 
3. concurrent balance mutability - appropriate locking so that one request completes its balance check and transfer before the other proceeds
4. immutable audit trail - once a ledger entry is created , it should not be updated or deleted , if the transaction was genuinely incorrect then we can do smtg like reversal transaction 
5. ledger reconciliation - whether total debits equal to the total credits , each wallet ka stored balance matches balance calculated from its ledger entries 

non functional requirements 
1. database architecture - database transaction locks to handle the concurrency safely 
2. data precision - amounts handled as 64-bit integer minor units (for example, paise for INR); floating-point types (`float`, `double`) are explicitly forbidden by SRS §6.1
3. security and validation - prevent users from sending the negative values to inflate balances

skill requirements 
1. simulate-transfer ( under agent skills )
2. local-check - A common agent skill that builds the project, runs the test suite, starts the local server, and invokes a read-only endpoint to confirm it works on a local machine.
3. second brain integration - brain-ingest , brain-query, brain-refine skills to manage the obsidian vault 


technologies 
backend - java and spring boot 
api - rest api 
database - sqllite ( discuss again )
database access- jdbc 
testing - postman
ci/cd pipeline - github ig 

external deliverables that needs to be done 
1. srs 
2. architecture and design - diagrams ( uml,er,architecture)
3. ADR - records explaining the technical decisions ( example - why sqlLite)
4. everytime PR's - review codes and track 

check these again 
1. autoated tests - unit , integration , end to end , negative tests 
2. agent skills - small reusable instructions/workflows for ai agents
	1. simulate-transfer - one agent skill which checks if the backend is working fine 