var xmlhttp = new XMLHttpRequest();
var url = "output.json";

xmlhttp.onreadystatechange = function () {
  if (this.readyState == 4 && this.status == 200) {
    var data = JSON.parse(this.responseText);
    CreateTableFromJSON(data);
  }
};
xmlhttp.open("GET", url, true);
xmlhttp.send();


function CreateTableFromJSON(data) {
  var isPassed = true
  Array.from(document.getElementsByClassName("title")).forEach(
    function(element) {
      element.innerHTML=data.main.environmentName;
    }
  ); 

  document.getElementById("footerlink").href=data.main.jenkinsLink;
  
  var arr = data.validationOutput
  var col = [];
  for (var i = 0; i < arr.length; i++) {
    for (var key in arr[i]) {
      if (col.indexOf(key) === -1) {
        col.push(key);
      }
    }
  }
  var table = document.createElement("table");

  var tr = table.insertRow(-1); // TABLE ROW.

  for (var i = 0; i < col.length; i++) {
    var th = document.createElement("th"); // TABLE HEADER.
    th.innerHTML = col[i];
    tr.appendChild(th);
  }

  for (var i = 0; i < arr.length; i++) {
    tr = table.insertRow(-1);
    for (var j = 0; j < col.length; j++) {
      var tabCell = tr.insertCell(-1);
      tabCell.innerHTML = arr[i][col[j]];
      console.log(arr[i][col[j]])
      if (arr[i][col[j]] == "false") {
        isPassed = false
      }
    }
  }

  var divContainer = document.getElementById("showData");
  divContainer.innerHTML = "";
  divContainer.appendChild(table);

  if (!isPassed) {
    const el = document.getElementById('status');
    el.classList.add('status-red')
    el.innerHTML = "Failed"
  }
}