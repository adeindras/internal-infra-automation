#!/usr/bin/env python3

import os, sys
import json
import gspread
import inspect 
from datetime import datetime

script_directory = os.path.dirname(os.path.abspath( 
  inspect.getfile(inspect.currentframe())))

GSHEETS_DOCUMENT_KEY = '1AaufRUNvGsb5X0-5IExnKNmoo-No_Fc8r3pLORgAwAE'
# GSHEETS_DOCUMENT_KEY = os.getenv("GSHEETS_DOCUMENT_KEY")
GOOGLE_APPLICATION_CREDENTIALS = os.getenv('GOOGLE_APPLICATION_CREDENTIALS')

gc = gspread.service_account(filename=GOOGLE_APPLICATION_CREDENTIALS)

# set the document key. it should be obtained from the link
s = gc.open_by_key(GSHEETS_DOCUMENT_KEY)

# set the worksheet name
ws = s.worksheet('DB Readiness - machine readable (WIP)')

list_of_dicts = ws.get_all_records()

try:
    f = open("latest.json", "w")
    x = json.dumps(list_of_dicts, indent=2)
    f.write(x)
    f.close()
except Exception as e:
    print(e)
    sys.exit(1)
