import os, sys
import json
import csv
import gspread
import inspect
import string
from datetime import datetime
import time
import gspread
import slack_notifier

from gspread_formatting import *

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

limited_column_alphabets = list(string.ascii_uppercase)

cwd = os.getcwd()

script_directory = os.path.dirname(os.path.abspath( 
  inspect.getfile(inspect.currentframe())))

GOOGLE_APPLICATION_CREDENTIALS = os.getenv('GOOGLE_APPLICATION_CREDENTIALS')

enable_send_slack_message_var = os.environ.get('ENABLE_SEND_SLACK_MESSAGE', 'true')

if enable_send_slack_message_var.lower() == "true":
    enable_send_slack_message = True
else:
    enable_send_slack_message = False

customer_name = os.getenv('CUSTOMER_NAME')
project_name = os.environ.get('PROJECT_NAME', 'justice')
environment_name = os.getenv('ENVIRONMENT_NAME')

cluster_name = '{}-{}-{}'.format(customer_name, project_name, environment_name)

# init
gc = gspread.service_account(filename=GOOGLE_APPLICATION_CREDENTIALS)

output_list = [re.sub('.csv$', '', x) for x in os.listdir('./result') if re.match(r".*.csv$", x) and re.match(r"^((?!summary).)*.csv$", x)]
output_list.sort()

# output_list = [re.sub('.csv$', '', x) for x in os.listdir('{}/result'.format(cwd)) if re.match(r".*.csv$", x) and re.match(r"^((?!summary).)*.csv$", x)]

single_table_test_cases = json.load(open('{}/config.json'.format(script_directory), 'r'))['single_table']

with open('result/summary.csv', 'r') as output_summary_file:
    csv_summary = csv.reader(output_summary_file)
    csv_summary_dict = csv.DictReader(output_summary_file)
    output_summary = [r for r in csv_summary]

# create sheet
sheet_command = 'create'
try:
    if sheet_command == 'create':
        # (customer_name, project_name, environment_name)
        sh = gc.create('[{}][{}] DB Infra Readiness Check {}'.format(customer_name, environment_name, str(datetime.now().strftime("%Y-%m-%d %H:%M UTC").split('.')[0])))

        spreadsheet_link = 'https://docs.google.com/spreadsheets/d/{}/'.format(sh.id)

        print(spreadsheet_link)

        ws_default = sh.worksheet('Sheet1')
        ws_default.update_title('Summary')

        # sh.share('fahmi.mochtar@accelbyte.net', perm_type='user', role='writer')
        sh.share('everyone@accelbyte.net', perm_type='user', role='commenter', notify=False)

    elif sheet_command == 'update':
        sh = gc.open_by_key(os.getenv('GSHEETS_DOCUMENT_KEY'))
        ws_default = sh.worksheet('Summary')
    else:
        sys.exit(1)

except gspread.exceptions.APIError:
    slack_notifier.send_slack_notification(cluster_name, spreadsheet_link, status=False)
    sys.exit(1)
except Exception as e:
    raise e

summary_stats_table = [['Stats', ''],
                        ['OK', '=countif(C2:C, "OK")'],
                        ['FAILED', '=countif(C2:C, "FAILED")'],
                        ['Total', '=counta(C2:C)']]

ws_default.update(output_summary, 'A1')
ws_default.update(summary_stats_table, 'E1', raw=False)

set_column_widths(ws_default, [('A', 200), ('B', 450)])

# set_column_width(ws_default, 'C', 100)

rule_true_summary = ConditionalFormatRule(
    ranges=[GridRange.from_a1_range('C2:C', ws_default)],
    booleanRule=BooleanRule(
        condition=BooleanCondition('TEXT_CONTAINS', ['OK']),
        format=CellFormat(textFormat=TextFormat(bold=True), backgroundColor=Color(0.56,0.9,0.71))
    )
)

rule_false_summary = ConditionalFormatRule(
    ranges=[GridRange.from_a1_range('C2:C', ws_default)],
    booleanRule=BooleanRule(
        condition=BooleanCondition('TEXT_CONTAINS', ['FAILED']),
        format=CellFormat(textFormat=TextFormat(bold=True), backgroundColor=Color(0.93,0.4,0.4))
    )
)

header_fmt = CellFormat(
    backgroundColor=Color(0, 0, 0),
    textFormat=TextFormat(bold=True, foregroundColor=Color(1, 1, 1)),
    horizontalAlignment='LEFT'
    )

header_ok_summary = CellFormat(
    backgroundColor=Color(0.56,0.9,0.71),
    textFormat=TextFormat(bold=True, foregroundColor=Color(0, 0, 0)),
    horizontalAlignment='LEFT'
    )

header_failed_summary = CellFormat(
    backgroundColor=Color(0.93,0.4,0.4),
    textFormat=TextFormat(bold=True, foregroundColor=Color(0, 0, 0)),
    horizontalAlignment='LEFT'
    )

rules_summary = get_conditional_format_rules(ws_default)
rules_summary.append(rule_true_summary)
rules_summary.append(rule_false_summary)
rules_summary.save()

format_cell_ranges(ws_default, [('A1:C1', header_fmt), ('E2', header_ok_summary), ('E3', header_failed_summary), ('E4', header_fmt)])

time.sleep(5)

for sheet in output_list:
    while True:
        try:
            ws = sh.add_worksheet(title="{}".format(sheet), rows=100, cols=8)

            # if failed:
            #   ws.update_tab_color({"red": 0.93, "green": 0.4, "blue": 0.4})

            with open('result/{}.csv'.format(sheet), 'r') as csvfile:
                data_reader = csv.reader(csvfile)
                data_list = [r for r in data_reader]

                # define default value
                max_sheet_column_alphabet = 'D'
                sheet_column_width = len(data_list[0])

                for idx, a in enumerate(limited_column_alphabets):
                    if idx+1 == sheet_column_width:
                        max_sheet_column_alphabet = a

                header_range = 'A1:{}1'.format(max_sheet_column_alphabet)
                additional_header_range = 'A5:{}5'.format(max_sheet_column_alphabet)

                if sheet in single_table_test_cases:
                    status_range = '{}6:{}'.format(max_sheet_column_alphabet, max_sheet_column_alphabet)
                else:
                    status_range = '{}2:{}'.format(max_sheet_column_alphabet, max_sheet_column_alphabet)
                        
                print('Updating {}'.format(sheet))

                ws.update(data_list, 'A1')

                # Formatting
                rule_true = ConditionalFormatRule(
                    ranges=[GridRange.from_a1_range(status_range, ws)],
                    booleanRule=BooleanRule(
                        condition=BooleanCondition('TEXT_CONTAINS', ['True']),
                        format=CellFormat(textFormat=TextFormat(bold=True), backgroundColor=Color(0.56,0.9,0.71))
                    )
                )

                rule_false = ConditionalFormatRule(
                    ranges=[GridRange.from_a1_range(status_range, ws)],
                    booleanRule=BooleanRule(
                        condition=BooleanCondition('TEXT_CONTAINS', ['False']),
                        format=CellFormat(textFormat=TextFormat(bold=True), backgroundColor=Color(0.93,0.4,0.4))
                    )
                )

                # Apply conditional formatting
                rules = get_conditional_format_rules(ws)
                rules.append(rule_true)
                rules.append(rule_false)
                rules.save()

                # Configure formatting for header
                if sheet in single_table_test_cases:
                    format_cell_range(ws, header_range, header_fmt)
                    format_cell_range(ws, additional_header_range, header_fmt)
                else:
                    format_cell_range(ws, header_range, header_fmt)
                
                set_column_width(ws, 'A:C', 325)
                # set_column_width(ws, 'D', 100)

            time.sleep(5)
        except IndexError as e:
            print('{}: {} is empty. Skipped.'.format(e, sheet))
        except gspread.exceptions.APIError(response='429'):
            time.sleep(30)
            continue
        break

slack_notifier.send_slack_notification(cluster_name, spreadsheet_link, status=True)
