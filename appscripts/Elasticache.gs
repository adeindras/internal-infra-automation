function elasticacheParse(data) {
  const elasticache = 10
  // Elasticache
  let jsonElasticacheObject = {}
  jsonElasticacheObject.type = "elasticache"
  jsonElasticacheObject.data = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][elasticache] == '') {
      break;
    }
    let elasticacheObject = {};
    elasticacheObject.name = data[i][elasticache]
    elasticacheObject.class = data[i][elasticache+1]
    jsonElasticacheObject.data.push(elasticacheObject);
  }
  return jsonElasticacheObject
}
