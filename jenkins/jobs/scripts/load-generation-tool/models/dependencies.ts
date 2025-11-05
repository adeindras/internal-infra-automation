import WSClient from "../api/wsclient";

export interface Dependencies {
	dshubWS?: WSClient;
	lobbyWS?: WSClient;
	chatWS?: WSClient;
	extraPayload?: object;
	accessToken: string;
	adminAccessToken: string;
	clientToken?: string;
	configData: any;
	seedData: any;
	userData: any;
}
