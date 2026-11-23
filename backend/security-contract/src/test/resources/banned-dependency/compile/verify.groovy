// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

// Red for the right reason: the rule, its message and the planted coordinate.
def log = new File(basedir, 'build.log').text
assert log.contains('BannedDependencies failed')
assert log.contains('kumbuka-security-contract carries no dependency outside the test scope')
assert log.contains('org.assertj:assertj-core:jar:3.27.7')
return true
