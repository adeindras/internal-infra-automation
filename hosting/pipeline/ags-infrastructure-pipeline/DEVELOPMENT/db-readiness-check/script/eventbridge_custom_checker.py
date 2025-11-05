import re
import json
import csv
import inspect 
import os, sys
from prettytable import PrettyTable
import def_evaluator as checker
import custom_evaluator as custom_checker

cwd = os.getcwd()

customer_name = os.environ.get('CUSTOMER_NAME', None)
project_name = os.environ.get('PROJECT_NAME', 'justice')
environment_name = os.environ.get('ENVIRONMENT_NAME', None)
aws_region = os.environ.get('AWS_REGION', None)

cluster_name = '{}-{}-{}'.format(customer_name, project_name, environment_name)

eventbridge_karpenter_suffix = [
    '-kpt-spot-int-rule',
    '-kpt-health-rule',
    '-kpt-ec2-rebalance-rule',
    '-kpt-ec2-state-chng-rule'
]

status_string_replace = {
    True: "OK",
    False: "FAILED"
}

def run_eventbridge_check(resource_type, summary_page_resource_list):

    sp_output = None

    # Placeholder for JSON keys, and result 
    resource_list = []
    resource_test_cases = []
    resource_test_cases_keys = []

    try:
        csv_file = open('result/{}.csv'.format(resource_type), 'w')
    except FileNotFoundError:
        print("result/{}.csv not found".format(resource_type))
        # break
    
    try:
        sp_output = json.load(open("{}/{}.json".format(cwd, resource_type), "r"))
        resource_list = [{"index": i, "name": sp_output['rows'][i]['resource_name']} for i in range(len(sp_output['rows'])) if re.match(".*{}-justice-{}.*".format(customer_name, environment_name), sp_output['rows'][i]['resource_name'])]

        # resource_list = [sp_output['rows'][i]['resource_name'] for i in range(len(sp_output['rows'])) if re.match(".*{}-justice-{}.*".format(customer_name, environment_name), sp_output['rows'][i]['resource_name'])]

        eventbridge_karpenter_test_list = ['{}{}'.format(cluster_name, eventbridge_karpenter_suffix[i]) for i in range(len(eventbridge_karpenter_suffix))]

    except KeyError as e:
        print("SKIP: {} key not found".format(e))
    except FileNotFoundError:
        print("{} not found".format(resource_type))
        # break

    # resource_test_cases = [test_cases[k] for k in range(len(test_cases)) if test_cases[k]['resource_type'] == resource_type]
    # resource_test_cases_keys = [resource_test_cases[k]['variables'] for k in range(len(resource_test_cases))]
    # resource_test_cases_key_labels = [resource_test_cases[k]['key_label'] for k in range(len(resource_test_cases))]

    resource_test_cases = [
        {
            "resource_type": "eventbridge",
            "variables": "resource_name",
            "evaluator": "regex",
            "operator": "null",
            "key_label": "Resource name",
            "value": ".*-kpt.*"
        },
        {
            "resource_type": "eventbridge",
            "variables": "exists",
            "evaluator": "regex",
            "operator": "null",
            "key_label": "Exists",
            "value": "True"
        }
    ]
    # resource_test_cases_keys = ["exists"]
    # resource_test_cases_key_labels = ["Exists"]
    # resource_test_cases_key_labels.append("Status")

    # resource_test_cases_keys = [resource_test_cases[k]['variables'] for k in range(len(resource_test_cases))]
    resource_test_cases_key_labels = [resource_test_cases[k]['key_label'] for k in range(len(resource_test_cases))]
    resource_test_cases_key_labels.append('Status')

    resource_test_cases_expected_value = [resource_test_cases[k]['value'] for k in range(len(resource_test_cases))]
    resource_test_cases_expected_value.append('Expected value')

    # for i in range(len(resource_test_cases_key_labels)):
    #     empty_list_placeholder.append("")

    t = PrettyTable(resource_test_cases_key_labels, align='l')

    if len(resource_list) == 0:
        print("WARN: Resources not found for {}".format(resource_type))

    resource_list_name_only = [resource_list[r]['name'] for r in range(len(resource_list))]

    # init csv
    csv_output_writer = csv.writer(csv_file)
    csv_output_writer.writerow(resource_test_cases_key_labels)
    csv_output_writer.writerow(resource_test_cases_expected_value)
    csv_output_writer.writerow([''])
    csv_output_writer.writerow(['Current state'])
    csv_output_writer.writerow(resource_test_cases_key_labels)

    for r in eventbridge_karpenter_test_list:

        resource_no_error_detected = True

        failed_test_case = []
        resource_row_values = []

        try:
            if r in resource_list_name_only:
                evaluator = True
            else:
                evaluator = False

            if evaluator is False:
                resource_no_error_detected = False

            resource_row_values.append(r)
            resource_row_values.append(evaluator)

                
        except TypeError as e:
            if resource_list_name_only is not None:
                print(e, "An unwanted error occured while processing {}: {} - {}".format(resource_type, r))
            else:
                pass
        except Exception as e:
            print(e, "An unwanted error occured while processing {}: {}".format(resource_type, r))
            raise e
        
        resource_row_values.append(resource_no_error_detected)

        csv_output_writer.writerow(resource_row_values)
        t.add_row(resource_row_values)

        # print(t)

        summary_page_resource_list.append([resource_type, r, status_string_replace[resource_no_error_detected]])
    print(t)
