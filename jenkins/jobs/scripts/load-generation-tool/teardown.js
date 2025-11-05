import { SharedArray } from "k6/data";
import { testcaseMaping as testCases } from './test/index.js';

const preDefinedData = new SharedArray('preDefinedData', function () {
  return JSON.parse(open('./config.json')).data;
});

const seedData = new SharedArray('seedData', function () {
  return JSON.parse(open('./config.json')).seed;
});

const usersData = new SharedArray('usersData', function () {
  return JSON.parse(open('./users_output.json'));
});
const testCaseNameArray = __ENV.testcaseName.split(',');

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

export default function() {
  const dep = {
    lobbyWS: undefined,
    accessToken: '',
    adminAccessToken: '',
    userData: usersData,
    seedData: seedData,
    configData: preDefinedData[0]
  }
  if (testCaseNameArray.indexOf('test_friends') > -1) {
    console.log('deleting friendships');
    testCases['friends']['delete_friendships'](dep);
  }
  // cleaning up test users
  console.log('deleting', usersData.length, 'users');
  testCases['iam']['delete_users'](dep);
  // cleaning up game clients
  console.log('deleting game clients');
  testCases['iam']['delete_clients'](dep);
}