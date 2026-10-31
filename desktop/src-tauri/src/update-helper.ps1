param([Parameter(Mandatory=$true)][string]$ConfigPath)
$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
try {
    Add-Type -AssemblyName PresentationFramework
    [xml]$markup = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" Width="420" Height="290" WindowStartupLocation="CenterScreen" WindowStyle="None" ResizeMode="NoResize" Background="#0D1116" Foreground="#E4EDF2" FontFamily="Segoe UI" Title="Atualizando SFLink">
 <Border BorderBrush="#234438" BorderThickness="1" Padding="32">
  <StackPanel>
   <TextBlock Text="SFLink" Foreground="#20E3AD" FontSize="17" FontWeight="SemiBold" Margin="0,0,0,25"/>
   <TextBlock Name="Status" Text="Preparando atualização…" FontSize="22" FontWeight="SemiBold" TextWrapping="Wrap"/>
   <TextBlock Name="Detail" Text="Seus arquivos e dispositivos serão preservados." FontSize="12" Foreground="#91A1B5" TextWrapping="Wrap" Margin="0,12,0,24"/>
   <ProgressBar Name="Progress" IsIndeterminate="True" Height="4" Foreground="#20E3AD" Background="#1D3029" BorderThickness="0"/>
   <Button Name="Close" Content="Fechar" Visibility="Collapsed" HorizontalAlignment="Left" Padding="16,6" Margin="0,16,0,0"/>
  </StackPanel>
 </Border>
</Window>
'@
    $window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $markup))
    $status = $window.FindName('Status'); $detail = $window.FindName('Detail')
    $progress = $window.FindName('Progress'); $close = $window.FindName('Close')
    $close.Add_Click({ $window.Close() })
    $script:stage = 'verify'; $script:installerProcess = $null
    $timer = New-Object Windows.Threading.DispatcherTimer
    $timer.Interval = [TimeSpan]::FromMilliseconds(200)
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $timer.Add_Tick({
        try {
            if ($script:stage -eq 'verify') {
                if ((Get-FileHash -LiteralPath $config.installer -Algorithm SHA256).Hash -ne $config.sha256) { throw 'O instalador não passou na verificação SHA-256.' }
                [IO.File]::WriteAllText($config.ready, 'ready')
                $script:stage = 'wait'
            } elseif ($script:stage -eq 'wait') {
                if (-not (Test-Path -LiteralPath $config.go)) {
                    if ([DateTime]::UtcNow -gt $deadline) { throw 'A preparação expirou. O aplicativo não foi substituído.' }
                    return
                }
                $parent = Get-Process -Id $config.parent -ErrorAction SilentlyContinue
                if ($parent -and $parent.StartTime.ToUniversalTime().Ticks.ToString() -eq $config.parentStart) { return }
                if ((Get-FileHash -LiteralPath $config.installer -Algorithm SHA256).Hash -ne $config.sha256) { throw 'O instalador foi alterado durante a preparação.' }
                $status.Text = 'Atualizando aplicativo…'
                $detail.Text = 'O SFLink abrirá automaticamente ao concluir.'
                $script:installerProcess = Start-Process -FilePath $config.installer -ArgumentList ('/S /UPDATE /D=' + $config.destination) -WindowStyle Hidden -PassThru
                $script:stage = 'install'
            } elseif ($script:stage -eq 'install') {
                $script:installerProcess.Refresh()
                if (-not $script:installerProcess.HasExited) { return }
                if ($script:installerProcess.ExitCode -notin @(0,3010)) { throw ('A instalação falhou (código ' + $script:installerProcess.ExitCode + '). Abra o SFLink novamente ou use o instalador manual.') }
                if (-not (Test-Path -LiteralPath $config.executable)) { throw 'O aplicativo atualizado não foi encontrado. Use o instalador manual.' }
                $status.Text = 'Abrindo SFLink…'
                Start-Process -FilePath $config.executable -WindowStyle Normal | Out-Null
                $timer.Stop(); $window.Close()
            }
        } catch {
            $timer.Stop(); [IO.File]::WriteAllText($config.error, $_.Exception.Message)
            $status.Text = 'Não foi possível atualizar'
            $detail.Text = $_.Exception.Message
            $progress.IsIndeterminate = $false
            $close.Visibility = 'Visible'
        }
    })
    $window.Add_ContentRendered({ $timer.Start() })
    $window.Add_Closed({ $timer.Stop() })
    $null = $window.ShowDialog()
} catch { [IO.File]::WriteAllText($config.error, $_.Exception.Message); exit 1 }
