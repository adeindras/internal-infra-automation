function rdsParse(data) {
  const rds = 1
  let rdsReplicas = [];
  // Let's create a stupid algorithm
  // handling RDS section
  let jsonRdsObject = {}
  jsonRdsObject.type = "rds"
  jsonRdsObject.data = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][rds] == '') {
      break;
    }
    let rdsObject = {};
    rdsObject.name = data[i][rds]
    rdsObject.class = data[i][rds+1]
    const rdsname = rdsObject.name
    if (rdsname.indexOf("replica") < 1) {
      jsonRdsObject.data.push(rdsObject);
    } else {
      rdsReplicas.push(rdsObject)
    }
  }
  // handling rds replica
  rdsReplicas.forEach(instance => {
    replicaInstanceName = instance.name
    for (let i = 0; i < jsonRdsObject.data.length; i++) {
      if (replicaInstanceName.toString().indexOf(jsonRdsObject.data[i].name.toString()) >= 0) {
        if (!jsonRdsObject.data[i].replicas) jsonRdsObject.data[i].replicas = []
        jsonRdsObject.data[i].replicas.push(instance)
      }
    }
  });

  return jsonRdsObject;
}