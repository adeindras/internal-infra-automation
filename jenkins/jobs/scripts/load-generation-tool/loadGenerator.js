import { testcaseMaping as testCases } from './test/index.js';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const preDefinedData = new SharedArray('preDefinedData', function () {
  return JSON.parse(open('./config.json')).data;
});

const seedData = new SharedArray('seedData', function () {
  return JSON.parse(open('./config.json')).seed;
});

const usersData = new SharedArray('usersData', function () {
  return JSON.parse(open('./users_output.json'));
});


export const options = {
  maxRedirects: 0,
  noVUConnectionReuse: true,
  summaryTrendStats: ['avg'],
  userAgent: 'LoadGenerationTool/1.0',
  scenarios: {
    LGT: {
      executor: 'constant-vus',
      vus: __ENV.NUMBER_OF_USER || 1,
      duration: __ENV.MAX_DURATION || '10m'
    },
  },
};

export default function () {
  const user = usersData[__VU-1];
  const accessToken = iamAPI.getTokenViaGrantType(
      preDefinedData.baseURLDirect, 
      user.emailAddress, 
      user.password, 
      seedData.gameClientId, 
      seedData.gameClientSecret
    );

  const serviceNameArray = __ENV.serviceName.split(",");
  const testCaseNameArray = __ENV.testcaseName.split(",");
  const allTestCaseNames = testCaseNameArray.join(",").split(",");
  for (let i = 0; i < serviceNameArray.length; i++) {
    const serviceName = serviceNameArray[i];
    const testCaseNames = testCaseNameArray[i].split(",");

    for (const testCaseName of allTestCaseNames) {
      if (testCases[serviceName] && testCases[serviceName][testCaseName]) {
        // console.log(`${testCases[serviceName]}`)
        const functionToCall = testCases[serviceName][testCaseName](preDefinedData[0], seedData[0], usersData, accessToken);
      }
    }
  }
}

export function teardown(data) {
  // cleaning up test users
  console.log('deleting', usersData.length, 'users');
  testCases['iam']['delete_users'](preDefinedData[0], seedData, usersData, null);
  // cleaning up game clients
  console.log('deleting game clients');
  testCases['iam']['delete_clients'](preDefinedData[0], seedData, usersData, null);
}