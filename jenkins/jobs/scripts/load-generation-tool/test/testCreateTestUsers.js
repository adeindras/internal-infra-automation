import exec from 'k6/execution';
import file from 'k6/x/file';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import * as iamAPI from "../api/iam.js"

const data = new SharedArray('base data', function () {
  return JSON.parse(open('../config.json')).data;
});

const seedData = new SharedArray('seedData', function () {
  return JSON.parse(open('../config.json')).seed;
});

export let options = {
  maxRedirects: 0,
  noVUConnectionReuse: true,
  summaryTrendStats: ['avg'],
  userAgent: 'LoadGenerationTool/1.0',
  scenarios: {
    LGT: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
    },
  },
};

let users = [];

export default function () {
  const param = {
    baseUrl: data[0].baseURLDirect,
    email: data[0].adminEmail,
    password: data[0].adminPassword,
    clientId: seedData[0].gameClientId,
    clientSecret: seedData[0].gameClientSecret ? seedData[0].gameClientSecret : '',
    gameNamespace: data[0].namespaceGame
  };

  let accessToken = iamAPI.loginViaAuthFlow(param.baseUrl, param.email, param.password, param.clientId, param.clientSecret);
  check(accessToken, {
    'admin login': a => a && a.json('access_token')
  });
  let createTestUser = iamAPI.createTestUser(param.baseUrl, param.gameNamespace, accessToken.json('access_token'), __ENV.NUMBER_OF_USER);
  check(createTestUser, {
    'test users created': r => r && r.status === 201 && r.json('data').length > 0
  });
  createTestUser.json('data').forEach((user, i, data) => {
    let friendIndex = i % 2 ? (i-1)%data.length : (i+1)%data.length;
    let userData = {
        "userId": user.userId,
        "friendId": data[friendIndex].userId,
        "emailAddress": user.emailAddress,
        "password": user.password
    };

    users.push(userData);
  });

  if (exec.test.options.scenarios.LGT.iterations === (exec.instance.iterationsCompleted+1) ) {
      let usersString = JSON.stringify(users, null, 2);
      file.writeString('users_output.json', usersString);
  }
}