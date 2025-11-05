import { check } from "k6";
import { sendNotificationToDS } from "../api/dshub.ts";
import { getTokenViaGrantType } from "../api/iam.js";
import WSClient from "../api/wsclient.js";
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.ts";

let connected;
async function callTestDSHub(ws: WSClient, baseUrl: string, namespace: string, dsId: string, accessToken: string) {
  //TODO: add connection check in the beginning, change the console log
  if (!connected) {
    connected = await ws.waitMessage(msg => msg && msg.data && JSON.parse(msg.data).topic === 'DSHUB_CONNECTED', 10);
  }
  checkResponse(sendNotificationToDS(baseUrl, namespace, dsId, {
    topic: 'TEST_MESSAGE',
    timestamp: new Date().toISOString(),
    payload: 'hello'
  }, accessToken), {
    'sent notification to DS': r => r && r.status === 200
  });
  checkResponse(await ws.waitMessage(msg => msg && msg.data && JSON.parse(msg.data).topic === 'TEST_MESSAGE', 10), {
    'received notification': r => r && r.data && JSON.parse(r.data).payload === 'hello'
  });
}

export async function testDSHub(dep: Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }
  
  callTestDSHub(dep.dshubWS!, dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.extraPayload!['dsId'], dep.adminAccessToken);
}