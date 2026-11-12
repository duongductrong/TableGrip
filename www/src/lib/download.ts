import releasesFeed from '../../../appcast/releases.json'

import { links } from '#/content/site'

/**
 * Direct installer links for the newest release, read at build time from appcast/releases.json (the file the release
 * workflow appends every release to, oldest first, so the newest is the highest build). The Linux .deb is not in that feed because it does not update
 * itself, so its URL follows the same asset naming the release workflow uses: TableGrip-<version>-<target>.<ext>.
 * The website workflow rebuilds when releases.json changes, so these links move to each new release on their own.
 */
export type DownloadTarget = 'macos-arm64' | 'macos-x64' | 'windows-x64' | 'linux-x64'
export type DownloadOs = 'macos' | 'windows' | 'linux'

type Feed = { version: string; build: number; targets: Partial<Record<DownloadTarget, { url: string; length?: number }>> }[]

const latest = (releasesFeed as Feed).reduce<Feed[number] | undefined>(
  (best, release) => (!best || release.build > best.build ? release : best),
  undefined,
)

export const latestVersion: string | undefined = latest?.version

const extension: Record<DownloadTarget, string> = {
  'macos-arm64': 'dmg',
  'macos-x64': 'dmg',
  'windows-x64': 'msi',
  'linux-x64': 'deb',
}

export const targetInfo: Record<DownloadTarget, { os: DownloadOs; label: string; short: string }> = {
  'macos-arm64': { os: 'macos', label: 'macOS (Apple silicon)', short: 'Apple silicon' },
  'macos-x64': { os: 'macos', label: 'macOS (Intel)', short: 'Intel Mac' },
  'windows-x64': { os: 'windows', label: 'Windows', short: 'Windows' },
  'linux-x64': { os: 'linux', label: 'Linux', short: 'Linux .deb' },
}

export const allTargets = Object.keys(targetInfo) as DownloadTarget[]

/** The installer's file name, as it lands in the Downloads folder. */
export function fileName(target: DownloadTarget): string {
  return latestVersion ? `TableGrip-${latestVersion}-${target}.${extension[target]}` : `TableGrip.${extension[target]}`
}

/** The direct download URL, or the releases page when no release has been published yet. */
export function downloadUrl(target: DownloadTarget): string {
  if (!latestVersion) return links.releases
  return (
    latest.targets[target]?.url ??
    `${links.github}/releases/download/v${latestVersion}/${fileName(target)}`
  )
}

/** Installer size in MB, when the feed records it. */
export function downloadSize(target: DownloadTarget): number | undefined {
  const length = latest?.targets[target]?.length
  return length ? Math.round(length / 1_000_000) : undefined
}

/** The default target for an OS, before (or without) any architecture detection. */
export function defaultTarget(os: DownloadOs): DownloadTarget {
  return os === 'macos' ? 'macos-arm64' : os === 'windows' ? 'windows-x64' : 'linux-x64'
}

type UAData = {
  platform?: string
  getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string }>
}

/**
 * Best guess at the visitor's installer. Returns undefined for phones, tablets and anything else TableGrip does not
 * run on. Macs default to Apple silicon; Chromium's architecture hint and the WebGL renderer name catch Intel Macs.
 */
export async function detectTarget(): Promise<DownloadTarget | undefined> {
  if (typeof navigator === 'undefined') return undefined
  const ua = navigator.userAgent
  const uaData = (navigator as Navigator & { userAgentData?: UAData }).userAgentData
  const platform = uaData?.platform || navigator.platform || ''

  if (/iPhone|iPad|iPod|Android/i.test(ua) || (/Mac/i.test(platform) && navigator.maxTouchPoints > 1)) return undefined
  if (/Win/i.test(platform) || /Windows/i.test(ua)) return 'windows-x64'
  if (/Linux|X11/i.test(platform) || /Linux|X11/i.test(ua)) return /CrOS/i.test(ua) ? undefined : 'linux-x64'
  if (!/Mac/i.test(platform) && !/Mac OS X/i.test(ua)) return undefined

  try {
    const hints = await uaData?.getHighEntropyValues?.(['architecture'])
    if (hints?.architecture === 'x86') return 'macos-x64'
    if (hints?.architecture === 'arm') return 'macos-arm64'
  } catch {
    // Fall through to the WebGL check.
  }
  try {
    const gl = document.createElement('canvas').getContext('webgl')
    const info = gl?.getExtension('WEBGL_debug_renderer_info')
    const renderer = info && gl ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : ''
    if (/Intel|AMD|Radeon/i.test(renderer)) return 'macos-x64'
  } catch {
    // No WebGL: keep the default.
  }
  return 'macos-arm64'
}
