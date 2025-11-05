function token_exchange_superuser() {
	BASE_URL="https://stage.accelbyte.io"
	globalClientIdAdminPortalDefault="0374a5badab74191911f1f891127a94c"
	globalUserEmailSuperuser=""
	globalUserPasswordSuperuser=""
	csvFIleInput=hosting-stg-500-email-id.csv
	csvFileOutput=hosting-stg-500-email-username.csv

	readonly resHeader=./resHeader.txt
	readonly resBody=./resBody.txt
	readonly rawJson=./rawJson.json

	tmpStateLogin=$(head /dev/urandom | tr -dc A-Za-z0-9 | head -c 32)
	tmpChallengeCode=$(head /dev/urandom | tr -dc A-Za-z0-9 | head -c 64)


	# #Generate Token
	# echo "===> Generate Super User Token"
	# echo -e ">Request Authorization"
	# curl -s --write-out "Response code : %{http_code}\n" \
	#   --location "${BASE_URL}/iam/v3/oauth/authorize?response_type=code&client_id=${globalClientIdAdminPortalDefault}&state=${tmpStateLogin}&code_challenge=${tmpChallengeCode}&code_challenge_method=plain" \
	#   --header "Accept: application/json" --dump-header ${resHeader} -o ${resBody}
	# requestID=$(cat ${resHeader} | grep 'request_id' | awk -F 'request_id=' '{print $NF}' | tr -d '\r')
	# if [[ ${requestID} == "" ]]; then
	#   echo -e "Authorization failed!\n--------------------"
	#   cat ${resHeader}
	#   exit 1
	# else
	#   echo "Authorization success"
	# fi

	# echo -e "\n>Authenticate user"
	# curl -s --write-out "Response code : %{http_code}\n" \
	#   --location --request POST "${BASE_URL}/iam/v3/authenticate" \
	#   --header 'Content-Type: application/x-www-form-urlencoded' \
	#   --header 'Accept: application/json' \
	#   --data-urlencode "user_name=${globalUserEmailSuperuser}" \
	#   --data-urlencode "password=${globalUserPasswordSuperuser}" \
	#   --data-urlencode "request_id=${requestID}" \
	#   --dump-header ${resHeader} -o ${resBody}
	# authCode=$(cat ${resHeader} | grep '?code=' | awk -F 'code=' '{print $NF}' | awk -F '&state' '{print $1}' | head -1 | tr -d '\r')
	# if [[ ${authCode} == "" ]]; then
	#   failed "Authentication failed!\n--------------------"
	# else
	#   echo "Authentication success"
	# fi

	# echo -e "\n>Token Exchange " ${globalUserEmailSuperuser}
	# curl -s --write-out "Response code : %{http_code}\n" \
	#   --location --request POST "${BASE_URL}/iam/v3/oauth/token" \
	#   --header 'Content-Type: application/x-www-form-urlencoded' \
	#   --header 'Accept: application/json' \
	#   --data-urlencode 'grant_type=authorization_code' \
	#   --data-urlencode "code=${authCode}" \
	#   --data-urlencode "code_verifier=${tmpChallengeCode}" \
	#   --data-urlencode "client_id=${globalClientIdAdminPortalDefault}" -o ${rawJson}
	# access_token=$(cat ${rawJson} | jq '.access_token' | tr -d '"' | tr -d '\r')
	# echo "TOKEN : "
	# echo ${access_token}

	echo -e "\n>Token Exchange " ${globalUserEmailSuperuser}
	curl -s --write-out "Response code : %{http_code}\n" \
	  --location --request POST "${BASE_URL}/iam/v3/oauth/token?code_challenge_method=plain" \
	  --header 'Content-Type: application/x-www-form-urlencoded' \
	  --header 'Accept: application/json' \
	  # uncomment below with basic auth <clientID:clientSecret> 
	  # --header 'authorization: Basic <base64 of clientID:clientSecret>' \
	  --data-urlencode 'grant_type=password' \
	  --data-urlencode "username=${globalUserEmailSuperuser}" \
	  --data-urlencode "password=${globalUserPasswordSuperuser}" -o ${rawJson}
	  # --data "grant_type=password&username=${globalUserEmailSuperuser}&password=${globalUserPasswordSuperuser}" -o ${rawJson}
	access_token=$(cat ${rawJson} | jq '.access_token' | tr -d '"' | tr -d '\r')


}

function read_csv_data(){
	token_exchange_superuser

	# curl -s --write-out "Response code : %{http_code}\n" \
	#   --request GET "${BASE_URL}/iam/v3/admin/namespaces/loadtest1696482051/users?emailAddress=loadtest-user10K7-101@example.com" \
	#   --header 'Accept: application/json' \
	#   --header "Authorization: Bearer ${access_token}"
	echo "email,id" > $csvFIleInput
	while IFS="," read -r email_column rec_column2; do
	  curl -s --write-out "Response code : %{http_code}\n" \
	    --request GET "${BASE_URL}/iam/v3/admin/namespaces/performancetest/users?emailAddress=${email_column}" \
	    --header 'Accept: application/json' \
	    --header "Authorization: Bearer ${access_token}" -o ${rawJson}
	  userName=$(cat ${rawJson} | jq '.userName' | tr -d '"' | tr -d '\r')
	  echo "${email_column},${userName}" >> $csvFIleInput
	done < <(tail -n +2 ${csvFileOutput})

}

read_csv_data