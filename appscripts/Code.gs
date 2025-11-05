// Function to convert sheet data to JSON format
function json(sheetName) {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = spreadsheet.getSheetByName(sheetName);
  
  if (sheet === null) {
    return 'Sheet not found';
  }
  const data = sheet.getDataRange().getValues();

  let jsonObject = {};
  jsonObject.resources = [];
  
  jsonObject.resources.push(rdsParse(data));
  jsonObject.resources.push(mskParse(data));
  jsonObject.resources.push(docDBParse(data));
  jsonObject.resources.push(elasticacheParse(data));
  jsonObject.resources.push(openSearchParse(data));
  jsonObject.services = parseServices(data);

  return JSON.stringify(jsonObject);
}

function getSharedEnvLists() {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = spreadsheet.getSheetByName("SHARED-ENV-LIST");
  
  if (sheet === null) {
    return 'Sheet not found';
  }
  const data = sheet.getDataRange().getValues(); 

  let envList = []
  for (var i = 0; i < data.length; i++) {
    if (data[i][0] == '') {
      break;
    }
    envList.push(data[i][0]);
  }

  let jsonData = {}
  jsonData.data = envList
  return JSON.stringify(envList)
}

function getSharedEnvCCULists() {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = spreadsheet.getSheetByName("SHARED-ENV-CCU-LIST");
  
  if (sheet === null) {
    return 'Sheet not found';
  }
  const data = sheet.getDataRange().getValues(); 

  let envList = []
  for (var i = 0; i < data.length; i++) {
    if (data[i][0] == '') {
      break;
    }
    envList.push(data[i][0]);
  }

  let jsonData = {}
  jsonData.data = envList
  return JSON.stringify(envList)
}

// Main function to handle GET requests
function doGet(e) {
  try {
    const q = e.parameter.q
    const ccu = e.parameter.ccu;
    const environment = e.parameter.environment;
    if (q == "sharedEnvLists") {
      const jsonData = getSharedEnvLists();
      return ContentService
              .createTextOutput(jsonData)
              .setMimeType(ContentService.MimeType.JSON);
    } else if (q == "sharedEnvCCUList") {
      const jsonData = getSharedEnvCCULists();
      return ContentService
              .createTextOutput(jsonData)
              .setMimeType(ContentService.MimeType.JSON);
    }
    if (ccu == '') return
    let sheetName = 'CCU' + ccu
    const jsonData = json(sheetName);
    return ContentService
            .createTextOutput(jsonData)
            .setMimeType(ContentService.MimeType.JSON);
  } catch (error) {
    return ContentService
          .createTextOutput(`An error occurred`)
          .setMimeType(ContentService.MimeType.TEXT);
  }
}