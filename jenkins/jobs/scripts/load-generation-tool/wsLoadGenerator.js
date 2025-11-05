import * as iamAPI from "./api/iam.js"
import { testcaseMaping as testCases } from './test/index.js';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { createTimer, durationStringToMillis, setErrorMetrics, uuid } from "./lib/utils.js";
import WSClient from "./api/wsclient.js";
import { setFailedMetrics, setMaxBackoffDuration, setRetryMetrics, setTimeoutMetrics } from "./api/httpwithretry.js";
import { getThresholds } from "./lib/get-thresholds.ts";
import { clearInterval, setInterval } from "k6/timers";

const preDefinedData = new SharedArray('preDefinedData', function () {
  return JSON.parse(open('./config.json')).data;
});

const seedData = new SharedArray('seedData', function () {
  return JSON.parse(open('./config.json')).seed;
});

const usersData = new SharedArray('usersData', function () {
  return JSON.parse(open('./users_output.json'));
});

const serviceNameArray = __ENV.serviceName.split(',');
const testCaseNameArray = __ENV.testcaseName.split(',');
const allTestCaseNames = testCaseNameArray.join(',').split(',');

const disconnectCounts = new Counter('disconnected');
const retryCounts = new Counter('retry');
const failedRetryCounts = new Counter('failed_retry');
const timeoutCounts = new Counter('timeout');
const errorsCounts = new Counter('errors');

const thresholds = getThresholds(serviceNameArray);

__ENV.maxBackoffDuration && setMaxBackoffDuration(parseInt(__ENV.maxBackoffDuration));
setRetryMetrics(retryCounts);
setTimeoutMetrics(timeoutCounts);
setFailedMetrics(failedRetryCounts);
setErrorMetrics(errorsCounts);

export const options = {
  maxRedirects: 0,
  noVUConnectionReuse: true,
  summaryTrendStats: ['avg', 'p(95)', 'p(99.99)', 'count'],
  systemTags: ['vu'],
  tags: {
    testid: uuid()
  },
  userAgent: 'LoadGenerationTool/1.0',
  scenarios: {
    LGT: {
      executor: 'per-vu-iterations',
      vus: __ENV.NUMBER_OF_USER || 1,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: thresholds,
  teardownTimeout: '15m'
};

export default async function() {
  await startWSWorker(exec.vu.idInTest, preDefinedData[0], seedData[0], usersData);
}

async function startWSWorker(index ,configData, seedData, userData) {
  const user = userData[index-1];
  let testDone = false;

  const getToken = iamAPI.loginViaAuthFlow(
      configData.baseURLDirect,
      user.emailAddress, 
      user.password, 
      seedData.gameClientId, 
      seedData.gameClientSecret,
  );
  
  const accessToken = getToken.json('access_token');
  const metrics = {
    errorMetric: errorsCounts,
    retryMetric: retryCounts,
    disconnectMetric: disconnectCounts,
    failedRetryMetric: failedRetryCounts
  }

  const wsClientLobby = new WSClient(`${configData.baseUrlWs}/lobby/`, accessToken, true, parseInt(__ENV.maxBackoffDuration), {}, {
    name: 'ws_lobby_connect'
  });
  wsClientLobby.setMetrics(metrics);
  wsClientLobby.connect();

  let wsClientChat;
  if (serviceNameArray.find(serviceName => serviceName === 'chat')) {
    wsClientChat = new WSClient(`${configData.baseUrlWs}/chat/`, accessToken, true, parseInt(__ENV.maxBackoffDuration), {}, {
      name: 'ws_chat_connect'
    });
    wsClientChat.setMetrics(metrics);
    wsClientChat.connect();
  }

  let wsClientDSHub;
  let clientToken;
  let dsId;
  if (serviceNameArray.find(serviceName => serviceName === 'dshub')) {
    const getTokenRes = iamAPI.iamGetTokenV3(configData.baseURLDirect, {
      grant_type: 'client_credentials'
    }, seedData.gameClientId, seedData.gameClientSecret);
    clientToken = getTokenRes.json('access_token');
    dsId = uuid();
    wsClientDSHub = new WSClient(`${configData.baseUrlWs}/dshub/`, clientToken, true, parseInt(__ENV.maxBackoffDuration), {
      'X-Ab-ServerID': dsId,
      'X-Ab-Custom': 'true'
    }, {
      name: 'ws_ds_connect'
    });
    wsClientDSHub.setMetrics(metrics);
    wsClientDSHub.connect();
  }
  
  const dependencies = {
    lobbyWS: wsClientLobby,
    chatWS: wsClientChat,
    dshubWS: wsClientDSHub,
    extraPayload: {
      dsId: dsId,
      excludeGlobal: !testCaseNameArray.find(testCase => testCase === 'test_social')
    },
    accessToken: accessToken,
    adminAccessToken: '',
    clientToken: clientToken,
    userData: user,
    seedData: seedData,
    configData: configData
  }
  
  let testDelay;
  const progressCheck = setInterval(() => {
    if(exec.instance.currentTestRunDuration <= durationStringToMillis(exec.test.options.scenarios.LGT.maxDuration)) return;
    testDone = true;
    clearInterval(progressCheck);
    wsClientLobby.disconnect();
    wsClientChat && wsClientChat.disconnect();
    wsClientDSHub && wsClientDSHub.disconnect();
    testDelay && testDelay.abort();
  }, 100);
  
  outerloop: while(exec.instance.currentTestRunDuration <= durationStringToMillis(exec.test.options.scenarios.LGT.maxDuration)) {
    if (wsClientLobby.gaveUpRetrying) break outerloop;
    wsClientLobby.sendMessage(
      'type: refreshTokenRequest\n' +
      `id: message123\n` +
      `token: ${accessToken}`
    );
    
    // scenario runners:
    for (let i = 0; i < serviceNameArray.length; i++) {
      const serviceName = serviceNameArray[i];
      for (const testCaseName of allTestCaseNames) {
        if (testDone) break outerloop;
        if (testCases[serviceName] && testCases[serviceName][testCaseName]) {
          try {
            switch(testCaseName) {
              case 'test_friends':
              case 'test_chat':
              case 'test_dshub':
                await testCases[serviceName][testCaseName](dependencies);
                break;
              default:
                testCases[serviceName][testCaseName](dependencies);
            }
          } catch(err) {
            console.error(err && err.message ? err.message : err);
            errorsCounts.add(1, {error: err && err.message ? err.message : err, vu: __VU.toString(), testCase: testCaseName});
          }
        }
      }
    }
    
    if (testDone) break outerloop;
    testDelay = createTimer(5+Math.random()*10);
    await testDelay.done;
  }
}