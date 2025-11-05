# Load Generator Tool

This project is aimed at utilizing [k6](https://k6.io/) for load testing web applications. k6 is a modern load testing tool, scriptable in JavaScript, and designed for ease of use.

## Getting Started

### Prerequisites

Before running the tests, ensure you have the following prerequisites installed:

- [k6](https://k6.io/docs/getting-started/installation/)
- Node.js (for running scripts)

### Installation

You can run the tool via Docker or directly via your terminal, the difference between it if you use docker you cannot live logging network fia fiddler (for now, but if you know how to do it please tell us); and if you run directly via terminal/wsl you can live logging network using [this](https://accelbyte.atlassian.net/wiki/spaces/~62723e9476b8d30068732a80/pages/3061973035/Guide+to+HTTPS+Traffic+Inspection+with+k6+test+in+WSL) tutorial

#### Docker installation
1. Clone the repository:
   ```git clone git@bitbucket.org:accelbyte/internal-infra-automation.git```
2. Change your directory to ```\internal-infra-automation\jenkins\jobs\scripts\load-generation-tool```
3. Buil docker image using this command ```docker build -t k6-load-generator-tool .```
4. Run with command ```./k6_runner.sh docker wsLoadGenerator.js/loadGenertor.sh <number of virtual user/ccu (e.g.2)> <running duration e.g.(1m for one minutes)> <service> <service_test_scencario>```

#### Local Installation
1. Clone the repository:
   ```git clone git@bitbucket.org:accelbyte/internal-infra-automation.git```
2. Change your directory to ```\internal-infra-automation\jenkins\jobs\scripts\load-generation-tool```
3. **IMPORTANT!** PLEASE INSTALL [xk6](https://github.com/grafana/xk6?tab=readme-ov-file#install-xk6) or run this command in your terminal ```go install go.k6.io/xk6/cmd/xk6@latest``` on your current folder
4. **IMPORTANT!** PLEASE INSTALL [xk6-file](https://github.com/avitalique/xk6-file?tab=readme-ov-file#build) BEFORE RUNNING or run this command in your terminal ```xk6 build v0.54.0 --with github.com/avitalique/xk6-file@latest``` after you finish installing xk6
5. Find and open ```\config.json.example``` and fill the correspondent value from it. and after finish rename the file to ```config.json```
6. Run with command ```./k6_runner.sh local wsLoadGenerator.js/loadGenertor.sh <number of virtual user/ccu (e.g.2)> <running duration e.g.(1m for one minutes)> <service> <service_test_scencario>```

### Scenarios available
| service_test_scenario                     | Service                | Description                                                                                            |
| ------------------------------------------|:-------------:         | ------------------------------------------------------------------------------------------------------ |
| iam_test, iam_test_3rd_party              | iam                    | test for iam service including get profile and update profile                                          |
| test_basic                                | basic                  | test for basic service including create basic proile, update and get basic profile                     |
| test_cloudsave                            | cloudsave              | test for cloudsave service including update player record and delete player record                     |
| test_chat                                 | chat                   | test for chat service including get chat topic                                                         |
| test_config                               | config                 | test for config service including get config                                                           |
| test_friends                              | friends                | test for friends service including get friend lists and get incoming friend requests                   |
| test_gdpr                                 | gdpr                   | test for gdpr service including get user deletion status and and get registered service configuration  |
| test_group                                | group                  | test for group service including get group                                                             |
| test_legal                                | legal                  | test for legal service including get aggreement and get eligibilities                                  |
| test_platform                             | platform               | test for platform service including update currencues, credit user wallet, get store and get user wallet transaction  | 
| test_session                              | session                | test for session service including create party, creage game session, and delete game test_session     |
| test_social                               | social                 | test for social service including admin update stat code, and get user profile                         |
| test_ugc                                  | ugc                    | test for ugc service including get contentv2 and get type                                              |
| test_match2                               | match2                 | test for matchmaking v2 including get match tiket                                                      |

### Runner available
| k6_runner       | description |
|--|--|
|loadGenerator| The k6 runner leverages virtual users to simulate and execute predefined test scenarios, effectively increasing the Requests Per Second (RPS) metric|
|wsLoadGenerator| The k6 runner utilizes a lobby WebSocket connection, where virtual users first establish and maintain a connection to the lobby before executing any predefined test scenarios. Once logged in, you can see these virtual users increase or number in grafana |