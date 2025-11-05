from cookiecutter.main import cookiecutter

import sys
import shutil

if len(sys.argv) < 5:
    print("Usage: run.py <path/to/template> <path/to/outputdir> <Maintenance Name> <Fiscal Quarter>", file=sys.stderr)
    sys.exit(1)

template_path = sys.argv[1]
output_dir = sys.argv[2]
maintenance_name = sys.argv[3]
fiscal_quarter = sys.argv[4]

context = { 'project_name': maintenance_name, 'fiscal_quarter': fiscal_quarter }

cookiecutter(template=template_path, no_input=True, overwrite_if_exists=True, output_dir=output_dir, extra_context=context)

