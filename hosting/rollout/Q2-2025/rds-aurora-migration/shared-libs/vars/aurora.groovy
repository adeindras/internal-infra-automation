void convertRdsToAuroraProvisioned(instanceType) {
    // Define the mapping of RDS instance types to Aurora instance types
    def rdsToAuroraMapping = [
        'db.t4g.small'    : 'db.t4g.medium',
        'db.t4g.medium'   : 'db.t4g.medium',
        'db.m6g.large'    : 'db.r6g.large',
        'db.m6g.xlarge'   : 'db.r6g.xlarge',
        'db.m6g.2xlarge'  : 'db.r6g.2xlarge',
        'db.m6g.4xlarge'  : 'db.r6g.4xlarge',
        'db.m6g.8xlarge'  : 'db.r6g.8xlarge',
        'db.m6g.12xlarge' : 'db.r6g.12xlarge',
        'db.m6g.16xlarge' : 'db.r6g.16xlarge'
    ]

    if (!rdsToAuroraMapping.containsKey(instanceType)) {
        throw new IllegalArgumentException("Unsupported RDS instance type: ${instanceType}")
    }

    return rdsToAuroraMapping[instanceType]
}

void convertRdsToAuroraServerlessACU(instanceType) {
    
    // Define RDS to Aurora Serverless ACU mapping
    def rdsToAuroraServerlessACU = [
      'db.t4g.small'    : ['min': 2, 'max': 8],
      'db.t4g.medium'   : ['min': 2, 'max': 8],
      'db.m6g.large'    : ['min': 4, 'max': 8],
      'db.m6g.xlarge'   : ['min': 4, 'max': 16],
      'db.m6g.2xlarge'  : ['min': 8, 'max': 32],
      'db.m6g.4xlarge'  : ['min': 16, 'max': 64],
      'db.m6g.8xlarge'  : ['min': 32, 'max': 128],
      'db.m6g.12xlarge' : ['min': 48, 'max': 192],
      'db.m6g.16xlarge' : ['min': 64, 'max': 256]
        // Add other mappings as needed
    ]
    
    if (!rdsToAuroraServerlessACU.containsKey(instanceType)) {
        throw new IllegalArgumentException("Unsupported RDS instance type for Aurora Serverless: ${instanceType}")
    }

    return rdsToAuroraServerlessACU[instanceType]
}