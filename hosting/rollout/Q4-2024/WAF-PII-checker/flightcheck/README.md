# Rollout Flight Check
Bash script to run pre and post check for infra rollout

## Flightcheck checks for:
- [ ] ratelimit 
  - [ ] Preflight
  - [x] Check target cluster
  - [x] Check running emissary version
  - [x] Check if critical workload nodes exist
  - [ ] Postflight
  - [x] Check if ratelimit pod is running
  - [x] Check if redis-ratelimit pod is running
  - [x] Check if Mappings have correct labels
- [ ] IAM V2 API Blocking
  - [ ] Requirements 
      - aws
      - jq
      - terminal session connected to target environment
  - [ ] Preflight -- None
  - [ ] Postflight
    - [x] Get Superuser user id and admin portal namespace
    - [x] Send GET Requests to ```/iam/v2/public/namespaces/{namespace}/users/*``` endpoint
    - [x] Send GET Requests to ```/iam/v2/public/namespaces/{namespace}/users/{userId}/bans``` endpoint
    - [x] Send GET Requests to ```/iam/v2/public/namespaces/{namespace}/users/{userId}/platforms/justice``` endpoint

## How to
  ### Run ratelimit pre/post flight
  ``` bash
  # run preflight check before ratelimit rollout
  bash flightcheck.sh ratelimit pre
  
  # run postflight check after ratelimit rollout
  bash flightcheck.sh ratelimit post

  # run both preflight and postflight for ratelimit rollout
  bash flightcheck.sh ratelimit

  ```
  ### Run iam-v2-api-blocking post flightcheck
  1. Edit the parameters in iam-v2-api-blocking.sh file
  ```bash
  CUSTOMER_NAME=accelbyte
  ENVIRONMENT_NAME=development
  HOSTNAME=development.accelbyte.io
  AWS_REGION=us-west-2
  ```
  2. Authenticate your terminal session with target aws account, check with aws sts ```get-caller-identity``` before proceeding to next step
  3. run postflight check after iam-v2-api-blocking rollout. DO NOT run the script within VPN or whitelisted network
  ``` bash
  bash flightcheck.sh iam-v2-api-blocking post

  ```


## Notes:
  1. ratelimit rollout as first rollout using pre/post flight approach
