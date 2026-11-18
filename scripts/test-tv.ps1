param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$PlaylistUrl,
    [string]$Adb = "adb",
    [switch]$AllLogos,
    [switch]$SkipSettings,
    [switch]$SkipSources,
    [switch]$SkipControls,
    [string]$LineChannel
)

$ErrorActionPreference = "Stop"
$package = "com.iptv.tv"
$dumpPath = "/data/local/tmp/iptv-qa.xml"

function Invoke-Adb {
    $output = & $Adb -s $Device @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($args[0])" }
    $output
}

function Send-Key([int[]]$Codes) {
    if ($Codes.Count -gt 4 -and @($Codes | Sort-Object -Unique).Count -eq 1) {
        Invoke-Adb shell ("input keyevent " + ($Codes -join " ")) | Out-Null
    } else {
        foreach ($code in $Codes) {
            Invoke-Adb shell input keyevent $code | Out-Null
            Start-Sleep -Milliseconds 400
        }
    }
}

function Get-Ui {
    Invoke-Adb shell uiautomator dump $dumpPath | Out-Null
    [xml]((Invoke-Adb shell cat $dumpPath) -join "")
}

function Wait-Ui([scriptblock]$Match, [string]$Description, [int]$Timeout = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    do {
        $ui = Get-Ui
        if (& $Match $ui) { return $ui }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description"
}

function Find-Text([xml]$Ui, [string]$Label) {
    $Ui.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -eq $Label } | Select-Object -First 1
}

function Select-Text([string]$Label) {
    $ui = Wait-Ui { param($tree) Find-Text $tree $Label } $Label
    $node = Find-Text $ui $Label
    $coordinates = [regex]::Matches($node.GetAttribute("bounds"), "\d+")
    $x = ([int]$coordinates[0].Value + [int]$coordinates[2].Value) / 2
    $y = ([int]$coordinates[1].Value + [int]$coordinates[3].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
}

function Require-Text([string]$Label) {
    Wait-Ui { param($tree) Find-Text $tree $Label } $Label | Out-Null
}

function Test-SourceForm([string]$Text, [int]$DeleteCount = 0) {
    $ui = Wait-Ui { param($tree) $tree.SelectSingleNode('//node[@class="android.widget.EditText"]') } "source editor"
    $edit = $ui.SelectSingleNode('//node[@class="android.widget.EditText"]')
    if ($edit.GetAttribute("focused") -ne "true") { throw "Source editor is not focused" }
    if ($DeleteCount -gt 0) { Send-Key (@(123) + (@(67) * $DeleteCount)) }
    Invoke-Adb shell input text $Text | Out-Null
    Require-Text $Text
    Select-Text "保存"
}

function Open-Tool([string]$Label) {
    $ui = Get-Ui
    if (!(Find-Text $ui "线路") -or !(Find-Text $ui "设置")) {
        Send-Key @(82)
        $ui = Wait-Ui { param($tree) Find-Text $tree "设置" } "toolbar"
    }
    $item = Find-Text $ui $Label
    if ($item.ParentNode.GetAttribute("selected") -ne "true") { Send-Key @(22) }
    Send-Key @(23)
}

function Open-Settings {
    Open-Tool "设置"
    Require-Text "立即刷新"
}

function Open-SourceManager {
    Open-Settings
    Select-Text "直播源"
    Require-Text $PlaylistUrl
}

function Get-PlaylistText {
    $response = Invoke-WebRequest -Uri $PlaylistUrl
    if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) }
    else { $response.Content }
}

function Switch-TestLine {
    $entries = @((Get-PlaylistText) -split "\r?\n" | Where-Object { $_.StartsWith("#EXTINF:") })
    $channelEntry = $entries | Where-Object { $_.EndsWith("," + $LineChannel) } | Select-Object -First 1
    if (!$channelEntry -or $channelEntry -notmatch 'group-title="([^"]+)"') { throw "Missing test channel group" }
    $group = $Matches[1]
    $members = @($entries | Where-Object { $_.Contains('group-title="' + $group + '"') })
    $index = [array]::IndexOf($members, $channelEntry)
    Send-Key @(21)
    for ($turn = 0; $turn -lt 12; $turn++) {
        if (Find-Text (Get-Ui) $group) { break }
        Send-Key @(22)
    }
    Require-Text $group
    Send-Key (@(19) * $members.Count)
    if ($index -gt 0) { Send-Key (@(20) * $index) }
    Require-Text $LineChannel
    Send-Key @(23)
    Open-Tool "线路"
    Require-Text "已选"
    $ui = Get-Ui
    $selected = (Find-Text $ui "已选").ParentNode
    $current = $selected.SelectSingleNode('node[@text!="已选"]').GetAttribute("text")
    $choices = @($ui.SelectNodes('//node[@focusable="true"]/node[@text!=""]') |
        ForEach-Object { $_.GetAttribute("text") } | Where-Object { $_ -notin @($current, "取消", "已选") })
    if ($choices.Count -lt 1) { throw "Test channel does not expose an alternative line" }
    $alternate = $choices[0]
    Select-Text $alternate
    Start-Sleep -Seconds 2
    Open-Tool "线路"
    Require-Text "已选"
    $marked = (Find-Text (Get-Ui) "已选").ParentNode
    if (!$marked.SelectSingleNode('node[@text="' + $alternate + '"]')) { throw "Line did not switch" }
    Select-Text $current
    Write-Output "PASS: switched and restored the selected line for $LineChannel"
}

if ((Invoke-Adb get-state) -ne "device") { throw "ADB device is not online" }
$before = ((Invoke-Adb shell dumpsys package $package) | Select-String "firstInstallTime=").Line.Trim()
if (!$before) { throw "Missing installed app" }
Invoke-Adb shell am force-stop $package | Out-Null
Invoke-Adb shell am start -W -n "$package/.MainActivity" | Out-Null
if (!$SkipSettings) {
Open-SourceManager
$ui = Get-Ui
if (Find-Text $ui "检查更新") { throw "Online update option still present" }
Send-Key @(4, 23)
$settings = Wait-Ui { param($tree) Find-Text $tree "立即刷新" } "settings"
if (Find-Text $settings "检查更新") { throw "Online update option still present" }

$launch = $settings.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "启动刷新*" } | Select-Object -First 1
$originalSwitch = ($launch.GetAttribute("text") -split "\s+")[-1]
$otherSwitch = if ($originalSwitch -eq "ON") { "OFF" } else { "ON" }
Select-Text $launch.GetAttribute("text")
Select-Text $otherSwitch
Require-Text ("启动刷新   " + $otherSwitch)
Select-Text ("启动刷新   " + $otherSwitch)
Select-Text $originalSwitch
Require-Text ("启动刷新   " + $originalSwitch)

$settings = Get-Ui
$interval = $settings.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "定时刷新*" } | Select-Object -First 1
$intervalLabel = $interval.GetAttribute("text")
Select-Text $intervalLabel
$chosenInterval = (Get-Ui).SelectSingleNode('//node[@selected="true"]/node[@text!=""]')
if (!$chosenInterval) { throw "Cannot identify the saved refresh interval" }
$previousInterval = $chosenInterval.GetAttribute("text")
$alternateInterval = if ($previousInterval -eq "每 1 小时   ON") { "OFF" } else { "每 1 小时   ON" }
Select-Text $alternateInterval
$intervalChanged = if ($alternateInterval -eq "OFF") { "定时刷新   OFF" } else { "定时刷新   每 1 小时   ON" }
Require-Text $intervalChanged
Select-Text $intervalChanged
Select-Text $previousInterval
Require-Text $intervalLabel
Select-Text "立即刷新"
Start-Sleep -Seconds 3
Send-Key @(4)
Write-Output "PASS: settings, startup toggle, refresh schedule, and manual refresh command"
}

if (!$SkipSources) {
Open-SourceManager
$sourceText = "127.0.0.1:39991"
$editedText = "127.0.0.1:39992"
$addedUrl = "http://$sourceText/playlist.m3u"
$editedUrl = "http://$editedText/playlist.m3u"
if (Find-Text (Get-Ui) $addedUrl) { throw "Temporary source already exists" }
Select-Text "添加直播源"
Test-SourceForm $sourceText
Select-Text $addedUrl
Select-Text "查看地址"
Require-Text $addedUrl
Send-Key @(4, 23)
Select-Text "直播源"
Select-Text $addedUrl
Select-Text "编辑地址"
Test-SourceForm $editedText $addedUrl.Length
Select-Text $editedUrl
Select-Text "删除此源"
Select-Text "确认删除直播源"
$ui = Get-Ui
if ((Find-Text $ui $addedUrl) -or (Find-Text $ui $editedUrl)) { throw "Temporary sources remain" }
Require-Text $PlaylistUrl
Send-Key @(4, 4)
Write-Output "PASS: source add, view, edit, delete and original source retention"
}

if (!$SkipControls) {
Send-Key @(23)
Require-Text "正在播出"
Send-Key @(23)
if (Find-Text (Get-Ui) "正在播出") { throw "OK did not close program information" }
Send-Key @(22)
if (Find-Text (Get-Ui) "正在播出") { throw "Right opened an overlay" }
Open-Tool "线路"
Require-Text "已选"
Send-Key @(4, 4)
Send-Key @(4)
Require-Text "退出 IPTV？"
Select-Text "取消"
Send-Key @(20)
Require-Text "正在播出"
Start-Sleep -Seconds 6
if ($LineChannel) { Switch-TestLine; Start-Sleep -Seconds 6 }
Write-Output "PASS: line selector, playback keys, program information and exit confirmation"
}

if ($AllLogos) {
    Send-Key @(21)
    $m3u = Get-PlaylistText
    $groups = [ordered]@{}
    foreach ($line in ($m3u -split "\r?\n")) {
        if (!$line.StartsWith("#EXTINF:") -or $line -notmatch 'tvg-logo="[^"]+"') { continue }
        if ($line -notmatch 'group-title="([^"]+)"') { throw "Expected grouped source" }
        $group = $Matches[1]
        $name = ($line -split ',(?=(?:[^"]*"[^"]*")*[^"]*$)', 2)[-1].Trim()
        if (!$groups.Contains($group)) { $groups[$group] = @() }
        $groups[$group] += "台标 $name"
    }
    $seen = [Collections.Generic.HashSet[string]]::new()
    $firstUi = Get-Ui
    $activeGroup = @($groups.Keys | Where-Object { Find-Text $firstUi $_ })
    if ($activeGroup.Count -ne 1) { throw "Cannot identify the active channel group" }
    $start = [array]::IndexOf([string[]]@($groups.Keys), [string]$activeGroup[0])
    for ($g = 0; $g -lt $groups.Count; $g++) {
        Send-Key @(22)
        $group = @($groups.Keys)[($start + $g + 1) % $groups.Count]
        $expected = @($groups[$group])
        $position = 0
        for ($offset = 0; $offset -lt $expected.Count; $offset += 5) {
            $last = [Math]::Min($offset + 4, $expected.Count - 1)
            $end = if ($offset -eq 0) { 0 } else { $last }
            if ($end -gt $position) { Send-Key (@(20) * ($end - $position)); $position = $end }
            $page = @($expected[$offset..$last])
            $ui = Wait-Ui {
                param($tree)
                $visible = @($tree.SelectNodes('//node[@content-desc!=""]') | ForEach-Object { $_.GetAttribute("content-desc") })
                @($page | Where-Object { $_ -notin $visible }).Count -eq 0
            } "$group logos $offset-$last" 40
            foreach ($name in $page) { $null = $seen.Add($name) }
        }
        Write-Output "PASS: $group, $($expected.Count) logos"
    }
    Write-Output "PASS: observed $($seen.Count) unique source logos"
}
if ($AllLogos) { Send-Key @(4) }
$after = ((Invoke-Adb shell dumpsys package $package) | Select-String "firstInstallTime=").Line.Trim()
if ($before -ne $after) { throw "App installation changed during the test" }
Write-Output "PASS: TV regression suite"
