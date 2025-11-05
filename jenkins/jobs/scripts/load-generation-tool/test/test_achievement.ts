import * as achievementAPI from "../api/achievement.ts";
import { getTokenViaGrantType } from "../api/iam.js";
import { updateStatsValue } from "../api/social.js";
import { checkResponse, uuid } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.ts";

function callTestAchievement(
  baseUrl: string,
  namespace: string,
  userId: string,
  statCode: string,
  excludeGlobal: boolean,
  accessToken: string,
  adminAccessToken: string
) {
  const userAchCode = `test-LGT-user-${uuid()}`.slice(0, 32);
  checkResponse(achievementAPI.createNewAchievement(baseUrl, namespace, {
    name: {
      "en": "elit-user"
    },
    description: {
      "en": "achievment test",
    },
    defaultLanguage: "en",
    achievementCode: userAchCode,
    global: false,
    incremental: false,
  }, adminAccessToken), {
    'created user achievement': r => r && r.status === 201
  });
  
  checkResponse(achievementAPI.unlockUserAchievement(baseUrl, namespace, userId, userAchCode, accessToken), {
    'unlock user achievement': r => r && r.status === 204
  })

  checkResponse(achievementAPI.deleteAchievements(baseUrl, namespace, userAchCode, adminAccessToken), {
    'deleted user achievement': r => r && r.status === 204
  });
  
  // global achievement part
  if (excludeGlobal) return;
  
  const globalAchCode = `test-LGT-global-${uuid()}`.slice(0, 32);
  checkResponse(achievementAPI.createNewAchievement(baseUrl, namespace, {
    name: {
      "en": "elit-global"
    },
    description: {
      "en": "achievment test",
    },
    statCode: statCode,
    defaultLanguage: "en",
    achievementCode: globalAchCode,
    global: true,
    incremental: true,
    goalValue: 1,
  }, adminAccessToken), {
    'created global achievement': r => r && r.status === 201
  });
  
  checkResponse(updateStatsValue(baseUrl, namespace, userId, statCode, 1, accessToken), {
    'updated stat value': r => r && r.status === 200
  });
  
  checkResponse(achievementAPI.claimGlobalAchievement(baseUrl, namespace, userId, globalAchCode, accessToken), {
    'claim global achievement': r => r && r.status === 202
  });
  
  checkResponse(achievementAPI.listGlobalAchievements(baseUrl, namespace, accessToken), {
    'list global achievement': r => r && r.status === 200
  });
  
  checkResponse(achievementAPI.deleteAchievements(baseUrl, namespace, globalAchCode, adminAccessToken), {
    'deleted global achievement': r => r && r.status === 204
  });
}

export function testAchievement(dep: Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }
  
  callTestAchievement(
    dep.configData.baseURLDirect,
    dep.configData.namespaceGame,
    dep.userData.userId,
    dep.configData.statCode,
    dep.extraPayload!['excludeGlobal'],
    dep.accessToken,
    dep.adminAccessToken);
}