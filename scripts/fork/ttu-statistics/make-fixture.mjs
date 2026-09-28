#!/usr/bin/env node
/*
 * Reikai JP: writes the fixture the JVM test TtuStatisticsTest holds the Kotlin port of ttu's reading
 * statistics to (roadmap 4.4). GPL-3.0-or-later, except the functions marked below.
 *
 * The functions between the markers are ッツ Ebook Reader's own, turned from TypeScript into plain
 * JavaScript and otherwise unchanged:
 *   updateStatistic        apps/web/src/lib/components/book-reader/book-reading-tracker/book-reading-tracker.svelte
 *   getStatisticsFileName  apps/web/src/lib/data/storage/handler/base-handler.ts
 *   sanitizeForFilename    apps/web/src/lib/data/storage/handler/base-handler.ts
 *   saveStatistics (the sort and JSON.stringify of backup-handler.ts)
 *
 * Run from the repository root; it rewrites app/src/test/java/jp/reikai/stats/TtuFixture.kt:
 *   node scripts/fork/ttu-statistics/make-fixture.mjs
 */
import { writeFileSync } from 'node:fs';

// --- ttu: begin ------------------------------------------------------------------------------------
// @license BSD-3-Clause
// Copyright (c) 2026, ッツ Reader Authors
// All rights reserved.
// (Licence text: LICENSES/BSD-3-Clause.txt.)

const exporterVersion = 1;
const currentDbVersion = 6;

function updateStatistic(statisticObject, timeDiff, characterDiff, lastStatisticModified) {
  const statistic = statisticObject;

  statistic.readingTime = Math.max(0, statistic.readingTime + timeDiff);
  statistic.charactersRead = Math.max(0, statistic.charactersRead + characterDiff);
  statistic.lastReadingSpeed = statistic.readingTime
    ? Math.ceil((3600 * statistic.charactersRead) / statistic.readingTime)
    : 0;
  statistic.minReadingSpeed = statistic.minReadingSpeed
    ? Math.min(statistic.minReadingSpeed, statistic.lastReadingSpeed)
    : statistic.lastReadingSpeed;
  statistic.maxReadingSpeed = Math.max(statistic.maxReadingSpeed, statistic.lastReadingSpeed);
  statistic.lastStatisticModified = lastStatisticModified;

  if (characterDiff) {
    statistic.altMinReadingSpeed = statistic.altMinReadingSpeed
      ? Math.min(statistic.altMinReadingSpeed, statistic.lastReadingSpeed)
      : statistic.lastReadingSpeed;
  }

  return statistic;
}

function getStatisticsFileName(statistics, lastStatisticModified) {
  let readingTime = 0;
  let charactersRead = 0;
  let minReadingSpeed = 0;
  let altMinReadingSpeed = 0;
  let maxReadingSpeed = 0;
  let weightedSum = 0;
  let validReadingDays = 0;
  let finishDate = 'na';

  for (let index = 0, { length } = statistics; index < length; index += 1) {
    const statistic = statistics[index];

    readingTime += statistic.readingTime;
    charactersRead += statistic.charactersRead;
    minReadingSpeed = minReadingSpeed
      ? Math.min(minReadingSpeed, statistic.minReadingSpeed)
      : statistic.minReadingSpeed;
    altMinReadingSpeed = altMinReadingSpeed
      ? Math.min(altMinReadingSpeed, statistic.altMinReadingSpeed)
      : statistic.altMinReadingSpeed;
    maxReadingSpeed = Math.max(maxReadingSpeed, statistic.lastReadingSpeed);
    weightedSum += statistic.readingTime * statistic.charactersRead;

    if (statistic.readingTime) {
      validReadingDays += 1;
    }

    if (statistic.completedData) {
      if (finishDate === 'na') {
        finishDate = statistic.dateKey;
      } else {
        finishDate =
          statistic.completedData.dateKey > finishDate
            ? statistic.completedData.dateKey
            : finishDate;
      }
    }
  }

  const averageReadingTime = validReadingDays ? Math.ceil(readingTime / validReadingDays) : 0;
  const averageWeightedReadingTime = charactersRead ? Math.ceil(weightedSum / charactersRead) : 0;
  const averageCharactersRead = validReadingDays
    ? Math.ceil(charactersRead / validReadingDays)
    : 0;
  const averageWeightedCharactersRead = readingTime ? Math.ceil(weightedSum / readingTime) : 0;
  const lastReadingSpeed = readingTime ? Math.ceil((3600 * charactersRead) / readingTime) : 0;
  const averageReadingSpeed = averageReadingTime
    ? Math.ceil((3600 * averageCharactersRead) / averageReadingTime)
    : 0;
  const averageWeightedReadingSpeed = averageWeightedReadingTime
    ? Math.ceil((3600 * averageWeightedCharactersRead) / averageWeightedReadingTime)
    : 0;

  return `statistics_${exporterVersion}_${currentDbVersion}_${lastStatisticModified}_${charactersRead}_${readingTime}_${minReadingSpeed}_${altMinReadingSpeed}_${lastReadingSpeed}_${maxReadingSpeed}_${averageReadingTime}_${averageWeightedReadingTime}_${averageCharactersRead}_${averageWeightedCharactersRead}_${averageReadingSpeed}_${averageWeightedReadingSpeed}_${finishDate}.json`;
}

function sanitizeForFilename(title) {
  return title
    .replace(/[ ]$/, '~ttu-spc~')
    .replace(/[.]$/, '~ttu-dend~')
    .replace(/\*/g, '~ttu-star~')
    .replace(/[/?<>\\:*|%"]/g, (match) => encodeURIComponent(match));
}

function saveStatisticsContent(data) {
  data.sort((a, b) => (a.dateKey > b.dateKey ? 1 : -1));
  return JSON.stringify(data);
}
// --- ttu: end --------------------------------------------------------------------------------------

function statistic(title, dateKey, fields = {}) {
  return {
    title,
    dateKey,
    charactersRead: 0,
    readingTime: 0,
    minReadingSpeed: 0,
    altMinReadingSpeed: 0,
    lastReadingSpeed: 0,
    maxReadingSpeed: 0,
    lastStatisticModified: 1790000000000,
    ...fields,
  };
}

// A reading day as the tracker sees it: seconds with and without page turns, a take-back after idle,
// a page read without a tick between, a take-back larger than the day holds.
const steps = [
  [1, 0], [1, 0], [1, 420], [1, 0], [1, 0], [1, 0], [1, 380], [1, 0], [2, 0], [1, 510],
  [1, 0], [-300, 0], [1, 0], [0, 350], [1, 0], [1, 1], [-5000, 0], [1, 0], [3, 90], [1, 0],
];
let running = statistic('吾輩は猫である', '2026-09-20', { lastStatisticModified: 0 });
const updates = steps.map(([timeDiff, characterDiff], index) => {
  const at = 1790000000000 + index * 1000;
  running = updateStatistic({ ...running }, timeDiff, characterDiff, at);
  return { timeDiff, characterDiff, at, after: { ...running } };
});

const titleA = '吾輩は猫である';
const titleB = 'Re:ゼロから始める異世界生活';
const sets = [
  {
    lastStatisticModified: 1790000123456,
    statistics: [
      statistic(titleA, '2026-09-22', {
        charactersRead: 12000, readingTime: 3600, minReadingSpeed: 9000, altMinReadingSpeed: 9500,
        lastReadingSpeed: 12000, maxReadingSpeed: 15000, lastStatisticModified: 1790000123456,
      }),
      statistic(titleA, '2026-09-20', {
        charactersRead: 3500, readingTime: 1800, minReadingSpeed: 5000, altMinReadingSpeed: 6000,
        lastReadingSpeed: 7000, maxReadingSpeed: 9000, lastStatisticModified: 1790000000000,
      }),
      statistic(titleA, '2026-09-21', { lastStatisticModified: 1790000050000 }),
      statistic(titleA, '2026-09-23', {
        charactersRead: 777, readingTime: 61, minReadingSpeed: 40000, altMinReadingSpeed: 45000,
        lastReadingSpeed: 45856, maxReadingSpeed: 50000, lastStatisticModified: 1790000100000,
      }),
    ],
  },
  {
    lastStatisticModified: 1790000999999,
    statistics: [
      statistic(titleB, '2026-09-24', {
        charactersRead: 5000, readingTime: 1000, minReadingSpeed: 17000, altMinReadingSpeed: 17500,
        lastReadingSpeed: 18000, maxReadingSpeed: 19000, lastStatisticModified: 1790000999999,
        completedBook: 1,
        completedData: {
          dateKey: '2026-09-26', charactersRead: 9000, readingTime: 2000, minReadingSpeed: 1,
          altMinReadingSpeed: 2, lastReadingSpeed: 3, maxReadingSpeed: 4,
        },
      }),
      statistic(titleB, '2026-09-23', {
        charactersRead: 4000, readingTime: 1000, minReadingSpeed: 14000, altMinReadingSpeed: 14400,
        lastReadingSpeed: 14400, maxReadingSpeed: 16000, lastStatisticModified: 1790000900000,
        completedBook: 1,
        completedData: {
          dateKey: '2026-09-25', charactersRead: 4000, readingTime: 1000, minReadingSpeed: 1,
          altMinReadingSpeed: 2, lastReadingSpeed: 3, maxReadingSpeed: 4,
        },
      }),
    ],
  },
  { lastStatisticModified: 1, statistics: [] },
];
const fileNames = sets.map(({ statistics, lastStatisticModified }) => ({
  statistics,
  lastStatisticModified,
  fileName: getStatisticsFileName(statistics, lastStatisticModified),
  // As an export from ttu's own database names it: its days come back sorted by day.
  sortedFileName: getStatisticsFileName(
    [...statistics].sort((a, b) => (a.dateKey > b.dateKey ? 1 : -1)),
    lastStatisticModified,
  ),
  content: saveStatisticsContent(statistics.map((s) => ({ ...s }))),
}));

const titles = [
  titleA, titleB, 'a/b?c<d>e\\f:g*h|i%j"k', 'ends with space ', 'two spaces  ', 'ends with dot.',
  'dots..', '***', 'plain',
];
const sanitized = titles.map((title) => ({ title, sanitized: sanitizeForFilename(title) }));

const fixture = JSON.stringify({ updates, fileNames, sanitized }, null, 1);
if (fixture.includes('"""') || fixture.includes('$')) throw new Error('fixture cannot be a Kotlin raw string');
const kotlin = `package jp.reikai.stats

/**
 * Generated by scripts/fork/ttu-statistics/make-fixture.mjs from ッツ Ebook Reader's own functions
 * (BSD-3-Clause, Copyright (c) 2026, ッツ Reader Authors): what ttu computes for these inputs. Do not
 * edit by hand; run the script again.
 */
internal object TtuFixture {
    const val JSON = """
${fixture}
"""
}
`;
writeFileSync('app/src/test/java/jp/reikai/stats/TtuFixture.kt', kotlin);
console.log(`wrote ${updates.length} updates, ${fileNames.length} file names, ${sanitized.length} titles`);
