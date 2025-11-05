import operator
import re

def evaluate_regex(source_value, expected_value):
    try:
        match = re.match(expected_value, source_value)
        return match
    except AssertionError:
        return False
    except SyntaxError:
        raise SyntaxError
    except Exception as e:
        print("An unexpected error occured:", e)
        raise e

def evaluate_operator(source_value, expected_value, math_operator="eq"):
    op = {
        'eq' : operator.eq,
        'ne' : operator.ne,
        'ge' : operator.ge,
        'le' : operator.le,
        'gt' : operator.gt,
        'lt' : operator.lt
    }

    result = op[math_operator.lower()](source_value, expected_value)
    return result

