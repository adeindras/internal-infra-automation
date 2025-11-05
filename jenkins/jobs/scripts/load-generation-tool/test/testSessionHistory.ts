import { getTokenViaGrantType } from "../api/iam.js";
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";
import * as sessionHistoryAPI from "../api/sessionhistory.ts";

export function callTestSessionHistory(baseUrl: string, namespaceGame: string, accessToken: string) {
  checkResponse(sessionHistoryAPI.getAllGameHistory(baseUrl, namespaceGame, {}, accessToken), {
    'get all game history': r => r && r.status === 200
  });
  
  // TODO: this need client token
  // checkResponse(sessionHistoryAPI.createTicketObservabilityRequest(baseUrl, namespaceGame, {
  //   action: 'matchFound',
  //   gameMode: 'mockGameMode',
  //   namespace: namespaceGame,
  //   ticketID: uuid(),
  //   timestamp: new Date().toISOString()
  // }, accessToken), {
  //   'create ticket obs request': r => r && r.status === 200
  // });
}

export function testSessionHistory(dep: Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestSessionHistory(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.adminAccessToken);
}