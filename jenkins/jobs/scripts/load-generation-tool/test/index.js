import { testIAM, deleteUsers, deleteClients } from './testIam.ts'
import { testCloudsave } from './testCloudsave.ts'
import { testChat } from './testChat.ts'
import { testConfig } from './testConfig.ts'
import { deleteFriendships, testFriends } from './testFriends.ts'
import { testGroup } from './testGroup.ts'
import { testPlatform } from './testPlatform.ts'
import { testSession } from './testSession.ts'
import { testSocial } from './testSocial.ts'
import { testUGC } from './testUGC.ts'
import { testBasic } from './testBasic.ts'
import { testLegal } from './testLegal.ts'
import { testGDPR } from './testGDPR.ts'
import { testMatch2 } from './testMatch2.ts'
import { testSessionHistory } from './testSessionHistory.ts'
import { testTurnmanager } from './test_turnmanager.ts'
import { testDSHub } from './test_dshub.ts'
import { testAchievement } from './test_achievement.ts'
import { testLobby } from './testLobby.ts'

export const testcaseMaping = {
	iam: {
		'test_iam': testIAM,
		'delete_users': deleteUsers,
		'delete_clients': deleteClients
	},
	basic: {
		'test_basic': testBasic
	},
	cloudsave: {
		'test_cloudsave': testCloudsave
	},
	chat: {
		'test_chat': testChat
	},
	config: {
		'test_config': testConfig
	},
	friends: {
		'test_friends': testFriends,
		'delete_friendships': deleteFriendships
	},
	gdpr: {
		'test_gdpr': testGDPR
	},
	group: {
		'test_group': testGroup
	},
	legal: {
		'test_legal': testLegal
	},
	platform: {
		'test_platform': testPlatform
	},
	session: {
		'test_session': testSession
	},
	sessionhistory: {
		'test_sessionhistory': testSessionHistory
	},
	social: {
		'test_social': testSocial
	},
	ugc: {
		'test_ugc': testUGC
	},
	match2: {
		'test_match2': testMatch2
	},
	turnmanager:
	{
		'test_turnmanager': testTurnmanager
	},
	dshub: {
		'test_dshub': testDSHub
	},
	achievement: {
		'test_achievement': testAchievement
	},
	lobby: {
		'test_lobby': testLobby
	}
}