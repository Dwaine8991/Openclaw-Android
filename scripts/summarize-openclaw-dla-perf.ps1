param(
  [Parameter(Mandatory = $true)]
  [string] $InputPath,
  [ValidateSet("table", "json", "csv")]
  [string] $Format = "table"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path -LiteralPath $InputPath)) {
  throw "Missing DLA perf log: $InputPath"
}

function Parse-PerfLine {
  param([string] $Line)
  if ($Line -notmatch "\[openclaw-dla-perf\]") {
    return $null
  }

  $result = [ordered]@{}
  foreach ($match in [regex]::Matches($Line, "(\w+)=([^\s]+)")) {
    $result[$match.Groups[1].Value] = $match.Groups[2].Value
  }
  if (!$result.Contains("id") -or !$result.Contains("phase")) {
    return $null
  }
  if ($result.Contains("elapsedMs")) {
    $result["elapsedMs"] = [int64] $result["elapsedMs"]
  }
  return [pscustomobject] $result
}

$events =
  Get-Content -LiteralPath $InputPath |
  ForEach-Object { Parse-PerfLine $_ } |
  Where-Object { $_ -ne $null }

$summaries =
  $events |
  Group-Object id |
  ForEach-Object {
    $byPhase = @{}
    foreach ($event in $_.Group) {
      $byPhase[$event.phase] = $event
    }

    $firstDelta =
      @(
        $byPhase["persistent_first_delta"],
        $byPhase["first_delta"],
        $byPhase["first_stdout"]
      ) |
      Where-Object { $_ -ne $null } |
      Sort-Object elapsedMs |
      Select-Object -First 1

    $completed =
      @(
        $byPhase["request_completed"],
        $byPhase["response_ready"],
        $byPhase["worker_closed"],
        $byPhase["persistent_completed"],
        $byPhase["request_failed"]
      ) |
      Where-Object { $_ -ne $null } |
      Sort-Object elapsedMs -Descending |
      Select-Object -First 1

    [pscustomobject]@{
      id = $_.Name
      promptBuiltMs = $byPhase["prompt_built"].elapsedMs
      promptChars = $byPhase["prompt_built"].promptChars
      requestedMaxTokens = $byPhase["prompt_built"].requestedMaxTokens
      maxTokens = $byPhase["prompt_built"].maxTokens
      nativeHeadersMs = $byPhase["native_response_headers"].elapsedMs
      firstByteMs = $byPhase["persistent_first_byte"].elapsedMs
      firstDeltaMs = $firstDelta.elapsedMs
      completedMs = $completed.elapsedMs
      mode = $completed.mode
      streamedChars = $completed.streamedChars
      textChars = $completed.textChars
      failedCode = $byPhase["request_failed"].code
    }
  } |
  Sort-Object completedMs, firstDeltaMs

switch ($Format) {
  "json" {
    $summaries | ConvertTo-Json -Depth 4
  }
  "csv" {
    $summaries | ConvertTo-Csv -NoTypeInformation
  }
  default {
    $summaries |
      Format-Table id, promptBuiltMs, promptChars, maxTokens, nativeHeadersMs, firstByteMs, firstDeltaMs, completedMs, mode, streamedChars, textChars, failedCode -AutoSize
  }
}
