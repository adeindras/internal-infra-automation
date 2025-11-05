import { Dependencies } from "../models/dependencies.ts";
import * as match2API from "../api/match2.js"
import { getTokenViaGrantType } from "../api/iam.js"
import { checkResponse, wsMessageToJSON } from "../lib/utils.js";

export function calltestMatch2(baseUrl:string, namespaceGame:string, matchPool:string, accessToken:string) {
  const createTicketRes = match2API.createMatchTicket(baseUrl, namespaceGame, {
    matchPool: matchPool,
    latencies: {
      'us-west-2': 60
    }
  }, accessToken);
  checkResponse(createTicketRes, {
    'created match ticket': r => r && r.status === 201
  });
  const getTicketRes = match2API.getMatchTicket(baseUrl, namespaceGame, accessToken); 
	checkResponse(getTicketRes, {
    'get match ticket': r => r && r.status === 200
  });
  
  checkResponse(match2API.deleteMatchTicket(
    baseUrl,
    namespaceGame,
    createTicketRes.json(`matchTicketID`),
  accessToken), {
    'delete match ticket': r => r && (r.status === 204 || r.status === 200)
  });
}

export function testMatch2(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  calltestMatch2(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.configData.matchPool, dep.accessToken);
}