<#
.SYNOPSIS
    Fails when a Metro-owned type is mis-owned: registered with Injekt as well, unscoped, or never
    annotated for the graph at all.

.DESCRIPTION
    During the Injekt-to-Metro port (docs/dev/plans/metro-di-migration.md) a type that moves into
    the graph has its Injekt registration deleted and is handed back through MetroInteropModule.
    Three ways that goes wrong, none of which surfaces at build or run time:

      1. Registered in both places, so the app runs with two instances and loses state silently.
      2. Handed back without @SingleIn(AppScope::class). Injekt's addSingletonFactory caches its
         result forever, but an unscoped Metro binding builds a new instance per injection, so
         Injekt callers and graph callers end up on different objects.
      3. Still Injekt-registered and never annotated, so the port walked past it.

    Injekt module files are discovered, not listed: a hard-coded list goes stale as the port deletes
    files, and a check that silently scans nothing reports success.
    Registrations made directly on Injekt, outside any module, are scanned on the same terms. The
    run also fails on a handed-back type nothing reads, allowing by name only the nine upstream hands
    back for the extension contract, which no code in this tree resolves and never will.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$srcRoots = @(
    'app/src/main/java'
    'domain/src/main/java'
    'data/src/main/java'
    'core/common/src/main/kotlin'
    'source-api/src/main/kotlin'
    'source-local/src/main/kotlin'
    'presentation-widget/src/main/java'
    'presentation-core/src/main/java'
    'core-metadata/src/main/java'
    'core/metro/src/main/kotlin'
    'core/archive/src/main/kotlin'
    'telemetry/src/main'
) | ForEach-Object { Join-Path $RepoRoot $_ }

# Every root has to exist. Dropping a missing one and carrying on is how a rename turns this into a
# check that scans less than it claims and still reports success.
$missingRoots = @($srcRoots | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missingRoots.Count -gt 0) {
    Write-Error "di-interop-check: source root(s) missing, the path list is stale:`n  $($missingRoots -join "`n  ")"
    exit 1
}

$sources = @(Get-ChildItem -LiteralPath $srcRoots -Recurse -Filter *.kt -File)
if ($sources.Count -lt 100) {
    Write-Error "di-interop-check: only $($sources.Count) Kotlin files found. The path list is stale."
    exit 1
}

$interopFile = Join-Path $RepoRoot 'app/src/main/java/mihon/app/di/injekt/MetroInteropModule.kt'
if (-not (Test-Path -LiteralPath $interopFile)) {
    Write-Host "di-interop-check: no interop module, nothing to check."
    exit 0
}

# Types the graph hands back: the constructor parameter types of MetroInteropModule.
$interopTypes = [System.Collections.Generic.List[string]]::new()
foreach ($line in Get-Content -LiteralPath $interopFile) {
    if ($line -match '^\s*private val \w+:\s*Provider<([A-Za-z0-9_.]+)>') {
        $interopTypes.Add(($Matches[1] -split '\.')[-1])
    } elseif ($line -match '^\s*private val \w+:\s*([A-Za-z0-9_.]+),\s*$') {
        $interopTypes.Add(($Matches[1] -split '\.')[-1])
    }
}

if ($interopTypes.Count -eq 0) {
    Write-Error "di-interop-check: parsed no types out of MetroInteropModule. The parser is stale."
    exit 1
}

# Every Injekt module in the tree, found by what it is rather than by where it used to live.
$moduleFiles = @($sources | Where-Object {
        [System.IO.File]::ReadAllText($_.FullName) -match '(?::\s*InjektModule\b|Injekt\.add\w+)'
    } | Where-Object { $_.FullName -ne $interopFile } | ForEach-Object { $_.FullName })

# Index every declaration with its annotation block and its supertype list, so an interface handed
# back by the interop module can be resolved to the implementation that carries the scope.
$declPattern = '(?m)^((?:@[\w\.]+(?:\([^\r\n]*\))?[ \t]*\r?\n)*)' +
'(?:public |internal |private |open |abstract |sealed |data |value |inner )*(?:class|object|interface) ' +
'([A-Za-z0-9_]+)[^\r\n{]*'
$decls = @{}
foreach ($file in $sources) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($m in [regex]::Matches($text, $declPattern)) {
        $name = $m.Groups[2].Value
        if ($decls.ContainsKey($name)) { continue }
        $decls[$name] = [pscustomobject]@{
            Path        = $file.FullName
            Annotations = $m.Groups[1].Value
            Header      = $m.Value
            HasMetro    = $text.Contains('dev.zacsweers.metro')
        }
    }
}

# Signatures are matched against whitespace-collapsed text, because a constructor or parameter list
# split across lines is exactly what a line-oriented pattern misses.
$flatScoped = @($sources | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        if ($text.Contains('@SingleIn(AppScope::class)')) { ($text -replace '\s+', ' ') }
    })

function Test-Scoped([string]$typeName) {
    $decl = $decls[$typeName]
    if ($decl -and $decl.Annotations -match '@SingleIn\(AppScope::class\)') { return $true }

    # An implementation bound to this interface, or a @Provides function returning it, may carry the
    # scope instead. The supertype match starts after the constructor's closing paren so a parameter
    # of this type cannot be mistaken for a supertype.
    $asSupertype = "@SingleIn\(AppScope::class\)[^{]{0,300}?\b(?:class|object) \w+ ?(?:\([^)]*\) ?)?: [^{]*?\b$typeName\b"
    $asReturnType = "@SingleIn\(AppScope::class\)[^{]{0,200}?\bfun \w+ ?\([^)]*\) ?: $typeName\b"
    foreach ($flat in $flatScoped) {
        if (-not $flat.Contains($typeName)) { continue }
        if ($flat -match $asSupertype -or $flat -match $asReturnType) { return $true }
    }
    return $false
}

$unscoped = @()
foreach ($type in $interopTypes) {
    if (-not (Test-Scoped $type)) {
        $unscoped += "  $type is handed back to Injekt without @SingleIn(AppScope::class)"
    }
}

$violations = @()
$registeredCtors = @()
$registeredTypes = @()
$unannotated = @()
foreach ($file in $moduleFiles) {
    $rel = $file.Substring($RepoRoot.Length).TrimStart('\', '/')
    $lines = Get-Content -LiteralPath $file
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -notmatch 'add(Singleton|Factory|SingletonFactory|LazySingleton)') { continue }

        # The constructor can sit on the trigger line or on the next one, which is how a multi-line
        # registration used to slip past both checks.
        $probe = if ($i + 1 -lt $lines.Count) { "$line`n$($lines[$i + 1])" } else { $line }

        $registered = $null
        $ctor = $null
        if ($line -match 'add\w+<\s*([A-Za-z0-9_.]+)\s*[>,]') {
            $registered = ($Matches[1] -split '\.')[-1]
        }
        if ($probe -match 'add\w+(?:<[^>]*>)?\s*\{\s*(?:\r?\n\s*)?([A-Z][A-Za-z0-9_]*)\s*\(') {
            $ctor = $Matches[1]
            if (-not $registered) { $registered = $ctor }
        }

        if ($registered) { $registeredTypes += $registered }
        if ($registered -and $interopTypes -contains $registered) {
            $violations += "  $rel`:$($i + 1) registers $registered, which MetroInteropModule already hands back"
        }
        if ($ctor) {
            $registeredCtors += $ctor
            $decl = $decls[$ctor]
            if ($decl -and -not $decl.HasMetro) {
                $unannotated += "  $rel`:$($i + 1) registers $ctor, whose class carries no Metro annotation"
            }
        }
    }
}

# Does anything still resolve these through Injekt? A registration nobody reads is not a failure,
# because a type reached only through another module's untyped get() is invisible here, but it is
# the list to look at before adding more.
$readTypes = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $sources) {
    if ($file.FullName -eq $interopFile) { continue }
    $text = [System.IO.File]::ReadAllText($file.FullName)
    if (-not $text.Contains('uy.kohesive.injekt')) { continue }
    foreach ($m in [regex]::Matches($text, '(?:Injekt\.get|Injekt\.getInstance|injectLazy)<\s*([A-Za-z0-9_.]+)')) {
        [void]$readTypes.Add(($m.Groups[1].Value -split '\.')[-1])
    }
    # The sites whose type sits on the declaration rather than the call, which a <T> pattern misses.
    foreach ($m in [regex]::Matches($text, ':\s*([A-Za-z0-9_.]+)(?:<[^>\r\n]*>)?\s*(?:get\(\)\s*)?(?:by injectLazy\(\)|=\s*Injekt\.get\(\))')) {
        [void]$readTypes.Add(($m.Groups[1].Value -split '\.')[-1])
    }
}

# A class a live module builds resolves its own constructor parameters through Injekt too, so walk
# that closure before calling anything unread.
function Get-ConstructorParamTypes([string]$typeName) {
    $decl = $decls[$typeName]
    if (-not $decl) { return @() }
    $text = [System.IO.File]::ReadAllText($decl.Path)
    $idx = [regex]::Match($text, "(?m)^(?:[\w ]*)\b(?:class|object) $([regex]::Escape($typeName))\b")
    if (-not $idx.Success) { return @() }
    $open = $text.IndexOf('(', $idx.Index)
    if ($open -lt 0) { return @() }
    $depth = 0
    $end = -1
    for ($i = $open; $i -lt $text.Length; $i++) {
        if ($text[$i] -eq '(') { $depth++ }
        elseif ($text[$i] -eq ')') { $depth--; if ($depth -eq 0) { $end = $i; break } }
    }
    if ($end -lt 0) { return @() }
    $params = $text.Substring($open + 1, $end - $open - 1)
    $found = @()
    foreach ($m in [regex]::Matches($params, ':\s*(?:Provider<|Lazy<|\(\)\s*->\s*)?([A-Za-z0-9_.]+)')) {
        $name = ($m.Groups[1].Value -split '\.')[-1]
        # Type names are capitalised; anything else came out of a comment inside the parameter list.
        if ($name -cmatch '^[A-Z]') { $found += $name }
    }
    return $found
}

# One hop, not the whole tree: a module builds these classes itself and resolves their constructor
# parameters through Injekt, but each of those parameters arrives already built, so its own
# dependencies come from Metro and are none of Injekt's business.
$closure = [System.Collections.Generic.HashSet[string]]::new()
$moduleResolved = [System.Collections.Generic.HashSet[string]]::new()
foreach ($type in $registeredCtors) {
    [void]$closure.Add($type)
    foreach ($param in Get-ConstructorParamTypes $type) {
        [void]$closure.Add($param)
        # The class itself is constructed by the module, never resolved, so only its parameters have
        # to come from somewhere.
        [void]$moduleResolved.Add($param)
    }
}

# The other direction, and the one that crashes: something resolves a type through Injekt that
# nothing registers any more. A module's own `get()` calls are untyped, so the closure above is the
# only way those reads are visible at all.
$knownRegistered = [System.Collections.Generic.HashSet[string]]::new()
foreach ($name in $interopTypes) { [void]$knownRegistered.Add($name) }
foreach ($name in $registeredTypes) { [void]$knownRegistered.Add($name) }
$mustResolve = [System.Collections.Generic.HashSet[string]]::new()
foreach ($name in $readTypes) { [void]$mustResolve.Add($name) }
foreach ($name in $moduleResolved) { [void]$mustResolve.Add($name) }
$unregistered = @($mustResolve | Where-Object { -not $knownRegistered.Contains($_) } | Sort-Object)
# Upstream hands these back for the extension contract, so they have no reader in this tree and never
# will. Anything else with no reader is either debt or a mistake, and fails below.
$upstreamContract = @(
    'Json', 'ProtoBuf', 'XML', 'NetworkHelper', 'JavaScriptEngine',
    'PreferenceStore', 'TrackPreferences', 'ExtensionManager', 'CoverCache'
)
$unread = @(
    $interopTypes |
        Where-Object { -not $readTypes.Contains($_) -and -not $closure.Contains($_) } |
        Where-Object { $upstreamContract -notcontains $_ } |
        Sort-Object -Unique
)

# The two ViewModel maps and the migration set are multibindings, so a member that forgets its key or
# its contribution compiles and then fails at the screen, or silently never runs on upgrade. Both are
# name-level checks, which is all the compiler leaves to do.
$keyedModels = [System.Collections.Generic.HashSet[string]]::new()
$keyedFactories = [System.Collections.Generic.HashSet[string]]::new()
$resolvedModels = [System.Collections.Generic.HashSet[string]]::new()
$resolvedFactories = [System.Collections.Generic.HashSet[string]]::new()
$unContributedMigrations = @()
foreach ($file in $sources) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    if ($text.Contains('@ViewModelKey')) {
        foreach ($m in [regex]::Matches($text, '(?m)^\s*(?:\w+ )*class ([A-Za-z0-9_]+)')) {
            [void]$keyedModels.Add($m.Groups[1].Value)
        }
    }
    # Keyed by the class that OWNS the factory, never by the factory's own name. Every one of these
    # interfaces is called Factory, so matching the bare name meant one keyed Factory anywhere in the
    # tree satisfied every resolution in the tree, and the rule passed on everything.
    if ($text.Contains('@ManualViewModelAssistedFactoryKey')) {
        # Indentation allowed: the owner is often a nested class, as MangaNotesScreen's Model is.
        foreach ($m in [regex]::Matches($text, '(?m)^\s*(?:\w+ )*class ([A-Za-z0-9_]+)')) {
            [void]$keyedFactories.Add($m.Groups[1].Value)
        }
    }
    foreach ($m in [regex]::Matches($text, 'assistedMetroViewModel<\s*[A-Za-z0-9_.]+\s*,\s*([A-Za-z0-9_]+)\.[A-Za-z0-9_]+\s*>')) {
        [void]$resolvedFactories.Add($m.Groups[1].Value)
    }
    # The Compose helper above is not the only route: an Activity resolves a manual factory by name
    # through createManuallyAssistedFactory, which fails at run time rather than at build time when
    # the key is missing, exactly like the Compose case.
    foreach ($m in [regex]::Matches($text, 'createManuallyAssistedFactory\(\s*([A-Za-z0-9_]+)\.[A-Za-z0-9_]+::class')) {
        [void]$resolvedFactories.Add($m.Groups[1].Value)
    }
    foreach ($m in [regex]::Matches($text, '(?<!assisted)metroViewModel<\s*([A-Za-z0-9_.]+)')) {
        [void]$resolvedModels.Add(($m.Groups[1].Value -split '\.')[-1])
    }
    # Whitespace-collapsed: a migration's supertype sits past a multi-line constructor, so a
    # line-oriented match never sees it and the rule would pass on everything.
    if (($text -replace '\s+', ' ') -match 'class [A-Za-z0-9_]+[^{]*: Migration\b' -and
        -not $text.Contains('@ContributesIntoSet')) {
        $unContributedMigrations += "  $($file.Name) implements Migration without @ContributesIntoSet"
    }
}
$unkeyed = @(
    @($resolvedModels | Where-Object { -not $keyedModels.Contains($_) } | ForEach-Object { "  $_ is resolved by metroViewModel but carries no @ViewModelKey" }) +
    @($resolvedFactories | Where-Object { -not $keyedFactories.Contains($_) } | ForEach-Object { "  $_'s assisted factory is resolved but carries no @ManualViewModelAssistedFactoryKey" })
)

$failed = $false
foreach ($group in @(
        @{ Message = 'a type is resolved through Injekt but registered nowhere.'; Items = @($unregistered | ForEach-Object { "  $_" })
            Hint = 'Register it, or convert its reader onto the graph. Nothing fails at build time.'
        },
        @{ Message = 'a ViewModel or assisted factory is resolved but never joined the map.'; Items = $unkeyed
            Hint = 'Add @ViewModelKey or @ManualViewModelAssistedFactoryKey plus @ContributesIntoMap, or the screen throws on open in every build type.'
        },
        @{ Message = 'a Migration is not contributed to the set that runs it.'; Items = $unContributedMigrations
            Hint = 'Add @ContributesIntoSet(AppScope::class), or it compiles and silently never runs on upgrade.'
        },
        @{ Message = 'a registered class was never annotated for the graph.'; Items = $unannotated
            Hint = 'Annotate it, or drop the registration if the type is genuinely retired.'
        },
        @{ Message = 'a type is registered with Injekt and handed back by Metro.'; Items = $violations
            Hint = 'Delete the Injekt registration, or drop the type from MetroInteropModule. Never both.'
        },
        @{ Message = 'a type is handed back to Injekt that nothing reads.'; Items = @($unread | ForEach-Object { "  $_" })
            Hint = 'Drop it. The only entries without a reader here are the nine upstream hands back for the extension contract, and those are allowed by name above.'
        },
        @{ Message = 'a type handed back to Injekt is not an application singleton.'; Items = $unscoped
            Hint = 'Injekt caches its instance forever while an unscoped graph binding builds a new one per injection, so the two halves of the app drift apart. Add @SingleIn(AppScope::class).'
        }
    )) {
    if ($group.Items.Count -gt 0) {
        $failed = $true
        Write-Host "di-interop-check FAILED: $($group.Message)" -ForegroundColor Red
        $group.Items | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Write-Host ""
        Write-Host $group.Hint
        Write-Host ""
    }
}
if ($failed) { exit 1 }

$moduleLabel = if ($moduleFiles.Count -eq 0) { 'no Injekt modules left' } else { "$($moduleFiles.Count) Injekt module(s)" }
Write-Host "di-interop-check: $($interopTypes.Count) graph-owned types, all scoped, $moduleLabel, no duplicates."

exit 0
