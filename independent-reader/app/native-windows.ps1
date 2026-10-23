param([ValidateSet('List','Watch')][string]$Mode='List',[long]$TargetHandle=0,[string]$TitleBase64='',[int]$ExcludeProcess=0,[int]$ParentPid=0)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class ReaderWindows {
  public delegate bool Callback(IntPtr h, IntPtr param);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
  [DllImport("user32.dll")] static extern bool EnumWindows(Callback callback,IntPtr param);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT rect);
  [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h,StringBuilder text,int max);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
  public sealed class Entry { public string handle; public string title; public uint pid; }
  public static Entry[] List(int exclude) {
    var entries=new List<Entry>();
    EnumWindows((h,p)=>{var title=new StringBuilder(1024); uint pid; GetWindowThreadProcessId(h,out pid); if(pid!=exclude && IsWindowVisible(h) && GetWindowText(h,title,1024)>0) entries.Add(new Entry {handle=h.ToInt64().ToString(),title=title.ToString(),pid=pid});return true;},IntPtr.Zero);
    return entries.ToArray();
  }
}
'@
if($Mode -eq 'List') { ConvertTo-Json -InputObject @([ReaderWindows]::List($ExcludeProcess)) -Compress; exit }
$targetTitle=if($TitleBase64){[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($TitleBase64))}else{''}
$startedAt=Get-Date
$deadline=$startedAt.AddHours(8)
while($true) {
  if($ParentPid -gt 0){ $parent=$null; try{$parent=Get-Process -Id $ParentPid -ErrorAction Stop}catch{}; if(-not $parent){exit 1} }
  if((Get-Date) -gt $deadline){exit 2}
  if($TargetHandle -eq 0 -and $targetTitle) {
    $candidate=@([ReaderWindows]::List($ExcludeProcess) | Where-Object {$_.title.IndexOf($targetTitle,[StringComparison]::OrdinalIgnoreCase) -ge 0})
    if($candidate.Count) {$TargetHandle=[long]$candidate[0].handle}
  }
  $handle=[IntPtr]::new($TargetHandle)
  $rect=[ReaderWindows+RECT]::new()
  $found=$TargetHandle -gt 0 -and [ReaderWindows]::IsWindow($handle)
  if($found -and [ReaderWindows]::GetWindowRect($handle,[ref]$rect)) {
    @{found=$true;minimized=[ReaderWindows]::IsIconic($handle);x=$rect.Left;y=$rect.Top;width=($rect.Right-$rect.Left);height=($rect.Bottom-$rect.Top)} | ConvertTo-Json -Compress
  } else { @{found=$false} | ConvertTo-Json -Compress; if($targetTitle){$TargetHandle=0} }
  Start-Sleep -Milliseconds 400
}
