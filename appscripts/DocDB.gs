function docDBParse(data) {
  const docdb = 8
  // DocDB
  let jsonDocDBObject = {}
  jsonDocDBObject.type = "docdb"
  jsonDocDBObject.data = []
  for (var i = 1; i < data.length; i++) {
    if (data[i][docdb] == '') {
      break;
    }
    let docdbObject = {};
    docdbObject.name = data[i][docdb]
    docdbObject.class = data[i][docdb+1]
    jsonDocDBObject.data.push(docdbObject);
  }
  return jsonDocDBObject
}
