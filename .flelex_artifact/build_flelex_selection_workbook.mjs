import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const [payloadPath, outputPath, previewDir] = process.argv.slice(2);
if (!payloadPath || !outputPath || !previewDir) {
  throw new Error(
    "Usage: node build_flelex_selection_workbook.mjs <payload.json> <output.xlsx> <preview-dir>",
  );
}

const payload = JSON.parse(await fs.readFile(payloadPath, "utf8"));
const columns = payload.columns;
const sheetOrder = ["A1", "A2", "B1", "B2", "C1", "C2"];
const numericColumns = new Set([
  "assigned_level_frequency",
  "total_frequency",
  "crf_A1",
  "crf_A2",
  "crf_B1",
  "crf_B2",
  "crf_C1",
  "crf_C2",
  "crf_total",
  "beacco_A1",
  "beacco_A2",
  "beacco_B1",
  "beacco_B2",
  "beacco_C1",
  "beacco_C2",
  "beacco_total",
]);

function excelColumnName(oneBasedIndex) {
  let number = oneBasedIndex;
  let name = "";
  while (number > 0) {
    number -= 1;
    name = String.fromCharCode(65 + (number % 26)) + name;
    number = Math.floor(number / 26);
  }
  return name;
}

function columnWidth(column) {
  if (["word", "crf_word", "beacco_word"].includes(column)) return 28;
  if (["level_source", "match_method"].includes(column)) return 21;
  if (["assigned_level_frequency", "total_frequency"].includes(column)) return 23;
  if (["assigned_cefr", "beacco_level"].includes(column)) return 14;
  if (["pos", "crf_pos", "beacco_pos"].includes(column)) return 12;
  return 13;
}

await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.mkdir(previewDir, { recursive: true });

const workbook = Workbook.create();
for (const sheetName of sheetOrder) {
  const rows = payload.sheets[sheetName] ?? [];
  const sheet = workbook.worksheets.add(sheetName);
  sheet.showGridLines = false;
  sheet.tabColor = "#1F4E78";

  const matrix = [columns, ...rows];
  const usedRange = sheet.getRangeByIndexes(0, 0, matrix.length, columns.length);
  usedRange.values = matrix;
  usedRange.format.font = { name: "Arial", size: 10, color: "#1F1F1F" };
  usedRange.format.verticalAlignment = "center";
  usedRange.format.wrapText = false;

  const header = sheet.getRangeByIndexes(0, 0, 1, columns.length);
  header.format = {
    fill: "#1F4E78",
    font: { name: "Arial", size: 10, bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "inside", style: "thin", color: "#FFFFFF" },
  };
  header.format.rowHeight = 32;
  sheet.getRangeByIndexes(1, 0, rows.length, columns.length).format.rowHeight = 18;

  for (let index = 0; index < columns.length; index += 1) {
    const columnRange = sheet.getRangeByIndexes(0, index, matrix.length, 1);
    columnRange.format.columnWidth = columnWidth(columns[index]);
    if (numericColumns.has(columns[index])) {
      sheet.getRangeByIndexes(1, index, rows.length, 1).format.numberFormat = "0.0000";
    }
  }

  sheet.freezePanes.freezeRows(1);
  sheet.freezePanes.freezeColumns(2);
  const lastColumn = excelColumnName(columns.length);
  const table = sheet.tables.add(
    `A1:${lastColumn}${matrix.length}`,
    true,
    `FlelexSelection_${sheetName}`,
  );
  table.style = "TableStyleMedium2";
  table.showFilterButton = true;
}

workbook.recalculate();

for (const sheetName of sheetOrder) {
  const inspection = await workbook.inspect({
    kind: "table",
    range: `${sheetName}!A1:L6`,
    include: "values,formulas",
    tableMaxRows: 6,
    tableMaxCols: 12,
    maxChars: 4500,
  });
  console.log(`INSPECT ${sheetName}\n${inspection.ndjson}`);

  const preview = await workbook.render({
    sheetName,
    range: "A1:L15",
    scale: 1,
    format: "png",
  });
  const previewPath = path.join(previewDir, `${sheetName}.png`);
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
  console.log(`PREVIEW ${sheetName}: ${previewPath}`);
}

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
  maxChars: 4500,
});
console.log(`FORMULA ERROR SCAN\n${errors.ndjson}`);

const output = await SpreadsheetFile.exportXlsx(workbook);
await output.save(outputPath);

const reopened = await SpreadsheetFile.importXlsx(await FileBlob.load(outputPath));
const reopenedInspection = await reopened.inspect({
  kind: "sheet",
  include: "id,name",
  maxChars: 4500,
});
console.log(`REOPENED WORKBOOK\n${reopenedInspection.ndjson}`);

const inspectSidecar = `${outputPath}.inspect.ndjson`;
try {
  await fs.unlink(inspectSidecar);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}
console.log(`WORKBOOK CREATED: ${outputPath}`);
