import operator
import re
import ast


def custom_eks_cluster_logging(source_value, environment_name='abcdexample-justice-prod'):
    current_value = ast.literal_eval(source_value)

    logging_enabled_prod = ['api', 'audit', 'authenticator']

    enabled_only = [v for v in current_value['ClusterLogging'][0]['Types'] if current_value['ClusterLogging'][0]['Enabled'] is True]
    status = enabled_only == logging_enabled_prod

    # return True
    return {"status": status, "result": enabled_only}

def custom_elasticache_multi_az_check(res_list, sp_output):
    # grouping based on name, then AZ
    pass


def evaluate(rule_name, source_value, **kwargs):
    if rule_name == 'eks_cluster_logging':
        # get enabled only
        result = custom_eks_cluster_logging(source_value, **kwargs)
        return result


