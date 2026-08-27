param(
    [switch]$Reset,
    [switch]$FullMatrix
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$integrationRoot = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRoot = Join-Path $integrationRoot 'server'
$pluginsRoot = Join-Path $serverRoot 'plugins'
$paperVersion = '1.21.4'
$paperBuild = '232'
$paperName = "paper-$paperVersion-$paperBuild.jar"
$paperPath = Join-Path $serverRoot $paperName

if ($Reset -and (Test-Path -LiteralPath $serverRoot)) {
    $resolved = (Resolve-Path -LiteralPath $serverRoot).Path
    if (-not $resolved.StartsWith($integrationRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove an unexpected path: $resolved"
    }
    # Keep Paper, dependency jars, and its long libraries cache; only clear
    # mutable plugin data and the generated test world between runs.
    foreach ($target in @(
        'plugins/RookieAuctions', 'plugins/Vault', 'plugins/Essentials',
        'plugins/.paper-remapped', 'plugins/bStats', 'plugins/spark',
        'world', 'world_nether', 'world_the_end', 'logs', 'crash-reports', 'spigot.yml'
    )) {
        $targetPath = Join-Path $resolved $target
        if (Test-Path -LiteralPath $targetPath) {
            Remove-Item -LiteralPath $targetPath -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

New-Item -ItemType Directory -Force -Path $serverRoot, $pluginsRoot | Out-Null

$paperUrl = 'https://fill-data.papermc.io/v1/objects/5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar'
if (-not (Test-Path -LiteralPath $paperPath)) {
    Write-Host "Downloading Paper $paperVersion build $paperBuild ..."
    Invoke-WebRequest -Uri $paperUrl -OutFile $paperPath -UseBasicParsing
}
$paperHash = (Get-FileHash -LiteralPath $paperPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($paperHash -ne '5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc') {
    throw "Paper checksum mismatch: $paperHash"
}

$pluginJar = Join-Path $repoRoot 'target/RookieAuctions.jar'
if (-not (Test-Path -LiteralPath $pluginJar)) {
    throw "Missing $pluginJar. Run mvn -DskipTests package first."
}
Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $pluginsRoot 'RookieAuctions.jar') -Force

$vaultJar = Join-Path $env:USERPROFILE '.m2/repository/com/github/MilkBowl/Vault/1.7.3/Vault-1.7.3.jar'
if (-not (Test-Path -LiteralPath $vaultJar)) {
    throw "Vault plugin jar not found in the local Maven cache: $vaultJar"
}
Copy-Item -LiteralPath $vaultJar -Destination (Join-Path $pluginsRoot 'Vault.jar') -Force

$essentialsJar = Join-Path $pluginsRoot 'EssentialsX-2.21.2.jar'
if (-not (Test-Path -LiteralPath $essentialsJar)) {
    $essentialsUrl = 'https://repo.essentialsx.net/releases/net/essentialsx/EssentialsX/2.21.2/EssentialsX-2.21.2.jar'
    Write-Host 'Downloading EssentialsX economy provider ...'
    Invoke-WebRequest -Uri $essentialsUrl -OutFile $essentialsJar -UseBasicParsing
}

Set-Content -LiteralPath (Join-Path $serverRoot 'eula.txt') -Value 'eula=true' -Encoding ascii
@'
motd=RookieAuctions integration test
server-port=25565
online-mode=false
enforce-secure-profile=false
spawn-protection=0
enable-command-block=true
enable-rcon=false
view-distance=6
simulation-distance=4
level-name=world
'@ | Set-Content -LiteralPath (Join-Path $serverRoot 'server.properties') -Encoding ascii

@'
[
  {
    "uuid": "01e439ad-f933-3f34-9425-b5e6f8dd13fb",
    "name": "TestAdmin",
    "level": 4,
    "bypassesPlayerLimit": true
  },
  {
    "uuid": "89e39568-2b73-325c-be25-4101438359fb",
    "name": "TestSeller",
    "level": 4,
    "bypassesPlayerLimit": true
  },
  {
    "uuid": "10e3a6fc-3572-3d9c-b74f-06d5db92b803",
    "name": "TestSeller2",
    "level": 4,
    "bypassesPlayerLimit": true
  },
  {
    "uuid": "cb3bd09b-744c-3603-b8f9-8f0c500d3e41",
    "name": "TestBidderA",
    "level": 4,
    "bypassesPlayerLimit": true
  },
  {
    "uuid": "b6849729-6607-38b2-8e9d-df0d6cd02149",
    "name": "TestBidderB",
    "level": 4,
    "bypassesPlayerLimit": true
  }
]
'@ | Set-Content -LiteralPath (Join-Path $serverRoot 'ops.json') -Encoding utf8

$configDir = Join-Path $pluginsRoot 'RookieAuctions'
New-Item -ItemType Directory -Force -Path $configDir | Out-Null
$configSource = Join-Path $repoRoot 'src/main/resources/config.yml'
$configPath = Join-Path $configDir 'config.yml'
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = Get-Content -LiteralPath $configSource -Raw -Encoding utf8
    if ($FullMatrix) {
        $now = Get-Date
        # Paper's first boot can spend ~90 seconds remapping plugins and generating
        # the test world.  Keep the first lock window comfortably in the future so
        # the bot can exercise submissions instead of racing the cutoff.
        $afternoon = $now.AddMinutes(5).ToString('HH:mm')
        $evening = $now.AddMinutes(14).ToString('HH:mm')
        $config = $config.Replace("time: '14:00'", "time: '$afternoon'")
        $config = $config.Replace("time: '20:00'", "time: '$evening'")
        $config = $config.Replace('capacity-per-session: 16', 'capacity-per-session: 3')
        $config = $config.Replace('submission-cutoff-seconds: 600', 'submission-cutoff-seconds: 90')
        $config = $config.Replace('lot-duration-seconds: 120', 'lot-duration-seconds: 30')
        $config = $config.Replace('intermission-seconds: 10', 'intermission-seconds: 3')
        $config = $config.Replace('missed-start-grace-seconds: 1800', 'missed-start-grace-seconds: 120')
        $config = $config.Replace('blocked-retry-seconds: 30', 'blocked-retry-seconds: 10')
        $config = $config.Replace('seconds-for-start: 30', 'seconds-for-start: 10')
        $config = $config.Replace('time: 30', 'time: 10')
    }
    Set-Content -LiteralPath $configPath -Value $config -Encoding utf8
}

Write-Host "Prepared test server at $serverRoot"
