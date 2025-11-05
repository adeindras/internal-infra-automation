import re
import json
import csv
import inspect 
import os, sys
import def_evaluator as checker
import custom_evaluator as custom_checker
import eventbridge_custom_checker
from prettytable import PrettyTable

cwd = os.getcwd()

script_directory = os.path.dirname(os.path.abspath( 
  inspect.getfile(inspect.currentframe())))

def check_env_var():
    var_not_found = False
    for i in ['CUSTOMER_NAME', 'ENVIRONMENT_NAME', 'AWS_REGION']:
        var = os.environ.get(i)
        if var == "" or var is None:
            print("{} variable is not set.".format(i))
            var_not_found = True
        
    if var_not_found == True:
        sys.exit(1)

customer_name = os.environ.get('CUSTOMER_NAME', None)
project_name = os.environ.get('PROJECT_NAME', 'justice')
environment_name = os.environ.get('ENVIRONMENT_NAME', None)
aws_region = os.environ.get('AWS_REGION', None)

if os.environ.get('DEBUG', 'false').lower() == "true":
    customer_name = "sandbox"
    project_name = "justice"
    environment_name = "dev"
    aws_region = "us-east-2"
else:
    check_env_var()

status_string_replace = {
    True: "OK",
    False: "FAILED"
}

test_cases = json.load(open("{}/latest.json".format(cwd), "r"))

case_exists = set()

available_test_cases = json.load(open('{}/config.json'.format(script_directory), 'r'))['single_table']

summary_page_header = ['Resource type', 'Name', 'Status']
summary_page_resource_list = []

if not os.path.exists('result/summary.csv'):
    summary_write_mode = 'w'
    summary_page_resource_list.append(summary_page_header)
else:
    summary_write_mode = 'a'

# Function for running check based on test cases based on resource type
def run_check_based_on_resource_type(resource_type):
    sp_output = None

    # Placeholder for JSON keys, and result 
    resource_list = []
    resource_test_cases = []
    resource_test_cases_keys = []

    try:
        csv_file = open('result/{}.csv'.format(resource_type), 'w')
    except FileNotFoundError:
        print("result/{}.csv not found".format(resource_type))
    
    try:
        sp_output = json.load(open("{}/{}.json".format(cwd, resource_type), "r"))
        if resource_type == 'rds':
            resource_list = [{"index": i, "name": sp_output['rows'][i]['resource_name']} for i in range(len(sp_output['rows'])) if re.match("rds-.*-justice-{}.*".format(environment_name), sp_output['rows'][i]['resource_name'])]
        elif resource_type == 'elasticache':
            resource_list = [{"index": i, "name": sp_output['rows'][i]['resource_name'], "cluster_name": sp_output['rows'][i]['cluster_name']} for i in range(len(sp_output['rows'])) if re.match(".*{}-justice-{}.*".format(customer_name, environment_name), sp_output['rows'][i]['resource_name'])]
        elif resource_type == 'kafka_topic_partitions':
            resource_list = [{"index": i, "name": sp_output['rows'][i]['resource_name']} for i in range(len(sp_output['rows']))]
        else:
            resource_list = [{"index": i, "name": sp_output['rows'][i]['resource_name']} for i in range(len(sp_output['rows'])) if re.match(".*{}-justice-{}.*".format(customer_name, environment_name), sp_output['rows'][i]['resource_name'])]
    except KeyError as e:
        print("SKIP: {} key not found".format(e))
    except FileNotFoundError:
        print("{} not found".format(resource_type))
        # break

    resource_test_cases = [test_cases[k] for k in range(len(test_cases)) if test_cases[k]['resource_type'] == resource_type]
    resource_test_cases_keys = [resource_test_cases[k]['variables'] for k in range(len(resource_test_cases))]
    resource_test_cases_key_labels = [resource_test_cases[k]['key_label'] for k in range(len(resource_test_cases))]
    resource_test_cases_key_labels.append("Status")

    resource_test_cases_expected_value = [resource_test_cases[k]['value'] for k in range(len(resource_test_cases))]
    resource_test_cases_expected_value.append('Expected value')

    t = PrettyTable(resource_test_cases_key_labels, align='l')

    if len(resource_list) == 0:
        print("WARN: Resources not found for {}".format(resource_type))

    # init csv
    csv_output_writer = csv.writer(csv_file)
    csv_output_writer.writerow(resource_test_cases_key_labels)
    csv_output_writer.writerow(resource_test_cases_expected_value)
    csv_output_writer.writerow([''])
    csv_output_writer.writerow(['Current state'])
    csv_output_writer.writerow(resource_test_cases_key_labels)

    for r in range(len(resource_list)):
        resource_name_idx = resource_list[r]['index']
        resource_name = resource_list[r]['name']

        rule_engine = 'regex'

        failed_test_case = []
        resource_row_values = []

        try:
            for idx, resource_case in enumerate(resource_test_cases_keys):
                try:
                    # print(idx, resource_case)
                    key_label = resource_test_cases[idx]['key_label']
                    current_value = str(sp_output['rows'][resource_name_idx][resource_case])
                    expected_value = str(resource_test_cases[idx]['value'])
                    rule_engine = str(resource_test_cases[idx]['evaluator'])

                    resource_no_error_detected = True

                    if rule_engine == 'regex':
                        evaluator = bool(checker.evaluate_regex(current_value, str(expected_value)))
                        resource_row_values.append(current_value)

                        if evaluator is False:
                            failed_test_case.append(resource_case)
                            resource_no_error_detected = False

                    elif rule_engine == 'operator':
                        op = resource_test_cases[idx]['operator']

                        if current_value.isalpha() is False:
                            if current_value.isnumeric() is True:
                                try:
                                    if int(current_value):
                                        current_value  = int(current_value)
                                        expected_value = int(expected_value)
                                except ValueError:
                                    pass
                            else:
                                try:
                                    if float(current_value):
                                        current_value  = float(current_value)
                                        expected_value = float(expected_value)
                                except ValueError:
                                    pass

                        evaluator = bool(checker.evaluate_operator(current_value, expected_value, math_operator=op))
                        resource_row_values.append(current_value)

                        if evaluator is False:
                            failed_test_case.append(resource_case)
                            resource_no_error_detected = False

                    elif rule_engine == 'custom':
                        op = resource_test_cases[idx]['operator']
                        evaluator = custom_checker.evaluate(op, current_value, environment_name='sandbox-justice-dev')
                        if evaluator is None:
                            resource_row_values.append(current_value)
                        else:
                            resource_row_values.append(current_value)

                        if evaluator['status'] is False or type(evaluator) == None:
                            failed_test_case.append(resource_case)
                            resource_no_error_detected = False


                except KeyError:
                    resource_row_values.append("")
                    # pass
                except TypeError:
                    if key_label is not None:
                        resource_row_values.append('TEST-CASE-ERROR')
                        csv_output_writer.writerow([key_label, current_value, expected_value, evaluator])
                        failed_test_case.append(resource_case)
                    elif resource_case is not None:
                        resource_row_values.append('TEST-CASE-ERROR')
                        csv_output_writer.writerow([resource_case, "TEST-CASE-ERROR", "TEST-CASE-ERROR", False])
                        failed_test_case.append(resource_case)
                    else:
                        pass

        except TypeError as e:
            if resource_case is not None:
                print(e, "An unwanted error occured while processing {}: {} - {}".format(resource_type, resource_name_idx, resource_case))
            else:
                pass
        except Exception as e:
            print(e, "An unwanted error occured while processing {}: {}".format(resource_type, resource_name_idx))
            raise e

        if len(failed_test_case) >= 1:
            resource_no_error_detected = False

        resource_row_values.append(resource_no_error_detected)

        csv_output_writer.writerow(resource_row_values)
        t.add_row(resource_row_values)

        summary_page_resource_list.append([resource_type, resource_name, status_string_replace[resource_no_error_detected]])

    csv_file.close()

    print("- ", resource_type)
    print(t)
    print("")

# Iterate resource type available in the test case sheet
for resource_type in available_test_cases:
    if resource_type == 'eventbridge':
        eventbridge_custom_checker.run_eventbridge_check(resource_type, summary_page_resource_list)
    else:
        run_check_based_on_resource_type(resource_type)

# Write summary csv
try:
    with open('result/summary.csv', summary_write_mode) as csv_summary_file:
        csv_summary_output_writer = csv.writer(csv_summary_file)
        csv_summary_output_writer.writerows(summary_page_resource_list)
        csv_summary_file.close()
except Exception as e:
    print('An unexpected error occurred: {}'.print(e))
    pass
