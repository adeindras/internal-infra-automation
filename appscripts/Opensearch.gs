function openSearchParse(data) {
  const opensearch = 12
  // opensearch
  let jsonOpensearchObject = {}
  jsonOpensearchObject.type = "opensearch"
  jsonOpensearchObject.data = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][opensearch] == '') {
      break;
    }
    let opensearchObject = {};
    opensearchObject.name = data[i][opensearch]
    opensearchObject.class = data[i][opensearch+1]
    jsonOpensearchObject.data.push(opensearchObject);
  }
  return jsonOpensearchObject
}
