import type {
  AgeBand,
  Book,
  Chapter,
  Draft,
  ReadThemeId,
  ReviewItem,
} from '../types';

// 全部为原创合成夹具；固定基准日期派生相对时间，不读系统时钟、不发起网络请求。

export const READ_THEMES: { id: ReadThemeId; label: string }[] = [
  { id: 'rice', label: '米白' },
  { id: 'green', label: '护眼绿' },
  { id: 'parchment', label: '羊皮纸' },
  { id: 'night', label: '夜间' },
];

const BASE_DATE = new Date('2026-11-12T00:00:00Z');

function isoDaysAgo(days: number): string {
  const date = new Date(BASE_DATE.getTime() - days * 86400000);
  return date.toISOString().slice(0, 10);
}

function relativeTime(hoursAgo: number): string {
  if (hoursAgo < 24) {
    return `${hoursAgo} 小时前`;
  }
  return `${Math.floor(hoursAgo / 24)} 天前`;
}

// 原创章节名池：按种子确定性取 8–12 章，保证任意时刻结果一致。
const CHAPTER_NAMES: string[] = [
  '潮水退去之后', '值班簿的第一页', '灯塔与旧信', '堤上的第三盏灯',
  '雾里的航标', '盐田的黄昏', '北风经过栈桥', '写给海的信',
  '礁石上的名字', '夜航船', '潮位表', '海图室',
  '修补渔网的人', '归港前一夜', '南巷的早点摊', '第一笼蒸汽',
  '老式收音机', '把名字写在纸上', '巷口的槐树', '雨棚下的座位',
  '冬夜的汤锅', '第二封未寄出的信', '城门开启时', '铜制齿轮',
  '机关铺的学徒', '雨夜的更鼓', '纸上的城池', '匠人名录',
  '北塔的灯', '失传的榫卯', '钟楼与暗道', '拆解一座桥',
  '雪落在瓦上', '信号中断的第七天', '无声频段', '记忆保管所',
  '白噪里的名字', '旧磁带', '城市失语之前', '最后一频道',
  '回声抵达之前', '深潜日志', '万米之下的光', '不存在的声呐',
  '科考船的夜班', '海沟来信', '浮标编号', '水面之上',
  '裁缝铺的午后', '一件改长的外套', '旧衣与旧事', '未缝合的袖口',
  '试衣镜前', '线头与线索', '把冬天收进抽屉', '纸鸢飞过北墙',
  '风从操场来', '地理课的下半节', '未寄出的明信片', '山道上的邮筒',
  '测绘笔记', '北回归线以南', '晨读开始之前', '星野下的课桌',
  '借来的铅笔', '朗读声', '第一排座位', '窗台上的薄荷',
];

function buildChapters(seed: number, count: number): Chapter[] {
  const chapters: Chapter[] = [];
  for (let i = 0; i < count; i += 1) {
    chapters.push({
      title: CHAPTER_NAMES[(seed * 7 + i * 5) % CHAPTER_NAMES.length],
      words: 2100 + ((i * 173 + seed * 97) % 1600),
      updatedAt: isoDaysAgo(1 + ((seed + i) % 14)),
    });
  }
  return chapters;
}

interface BookSeed {
  id: string;
  title: string;
  author: string;
  category: string;
  band: AgeBand;
  minAge: 0 | 14 | 16 | 18;
  state: Book['state'];
  words: number;
  hoursAgo: number;
  cover: [string, string];
  tags: string[];
  summary: string;
  intro: string;
  sig: string;
  chapterCount: number;
  seed: number;
}

const SEEDS: BookSeed[] = [
  {
    id: 'b1', title: '晚潮记', author: '林砚', category: '纪事文学',
    band: 'C2', minAge: 16, state: 'published', words: 41.1, hoursAgo: 3,
    cover: ['#F3EDE2', '#2A2724'], tags: ['纪事', '海边', '值班簿'],
    summary: '沿海小城的潮位观测员，从旧值班簿里翻出一段被涂掉的记录。',
    intro: '沿海小城的潮位观测员在整理旧值班簿时，发现一行被墨迹盖住的记录。顺着那行字，她重新走访三座早已停用的灯塔，把父辈沉默的往事、一次没有写进档案的救援，以及自己迟迟没有寄出的信，一并放回潮水里。潮水每晚照旧涨落，人事却终于有了可以安放的位置。',
    sig: '把潮位抄进本子，也把人情留在岸上。', chapterCount: 12, seed: 7,
  },
  {
    id: 'b2', title: '山月与旧信', author: '南洲', category: '乡土散文',
    band: 'C0', minAge: 0, state: 'published', words: 8.6, hoursAgo: 5,
    cover: ['#F6F0E4', '#3A352E'], tags: ['山乡', '家书', '四时'],
    summary: '一封迟到二十年的家书，牵出祖孙之间未曾说出口的约定。',
    intro: '山坳里的邮路改道之后，一封信在旧木匣中躺了整整二十年。孙女替祖母整理阁楼时读到它，才明白每年秋后晒在院中的桂花，原本是等一个人回来。故事沿着四时农事慢慢展开，没有激烈的波折，只有山月一次次照过同一扇窗，把迟到的牵挂收拢成一句平常话。',
    sig: '山月不催人，只把没说完的话照亮。', chapterCount: 8, seed: 3,
  },
  {
    id: 'b3', title: '机巧京城', author: '墨工', category: '东方奇想',
    band: 'C3', minAge: 18, state: 'published', words: 78.9, hoursAgo: 26,
    cover: ['#E8EDE6', '#26302B'], tags: ['机关', '群像', '匠作'],
    summary: '以机关术立足的少年，踏入皇城与匠籍交织的棋局。',
    intro: '京城的城门由九百六十枚铜销咬合，每夜开合都要经过匠籍册上的三次签押。来自南郊机关铺的少年被召去修一枚失灵的销子，却在其中发现前朝工匠留下的暗记。随着更鼓一层层敲过去，他必须在师门、工部与城里普通人的生计之间，找出不让任何一枚齿轮空转的办法。',
    sig: '每一件器物都该替使用它的人说话。', chapterCount: 12, seed: 11,
  },
  {
    id: 'b4', title: '白噪之城', author: '林深', category: '近未来',
    band: 'C4', minAge: 18, state: 'published', words: 19.6, hoursAgo: 30,
    cover: ['#ECE9F2', '#2B2733'], tags: ['记忆', '城市', '慢叙事'],
    summary: '当记忆可以被交易，一座城市在均匀的白噪里渐渐失语。',
    intro: '城里的记忆交易所开在旧电台二楼，人们用一段往事换另一段更轻的往事。值夜班的录音师发现，被换走的片段并没有消失，而是混进了全城广播的底噪。她沿着一条几乎听不见的杂音往前找，最后要决定的不是找回什么，而是这座城市还愿不愿意记得自己原本的声音。',
    sig: '静下来，才听得见被让渡的部分。', chapterCount: 9, seed: 5,
  },
  {
    id: 'b5', title: '巷口早餐铺', author: '春和', category: '都市日常',
    band: 'C0', minAge: 0, state: 'published', words: 15.4, hoursAgo: 50,
    cover: ['#F7EDE3', '#3B2F28'], tags: ['市井', '烟火', '邻里'],
    summary: '一条老巷、一家早餐铺，盛着街坊们最朴素的心事。',
    intro: '天还没亮，蒸笼的第一股白气就先于路灯亮起来。铺子主人记得每位常客的口味，也记得谁最近忽然不再来了。故事从一碗豆浆写到巷子拆迁前的最后一个冬天，没有宏大的转折，只有人们把难以开口的近况，托付给一句「今天还是老样子」的默契。',
    sig: '热汤端上来，话也就好说了。', chapterCount: 8, seed: 2,
  },
  {
    id: 'b6', title: '霜河渡', author: '北客', category: '历史悬疑',
    band: 'C2', minAge: 16, state: 'inreview', words: 53.2, hoursAgo: 52,
    cover: ['#E9EDF0', '#2B3238'], tags: ['边城', '渡口', '案卷'],
    summary: '边贸小城的一桩渡口命案，牵出两国之间未曾落笔的暗流。',
    intro: '封冻前的最后一次渡船靠岸时，船工在缆绳下发现一枚不属于本地的铜牌。县衙的案卷只写了三行，驿站往来簿却厚得反常。奉命复核的年轻书吏沿着河岸逐户询问，把商队、驿卒与守渡人的说法一一对照，终于明白这桩案子真正要藏的，并不是凶手的名字。',
    sig: '河面会封，账目不会。', chapterCount: 11, seed: 9,
  },
  {
    id: 'b7', title: '深海回声', author: '鲸落', category: '科考纪事',
    band: 'C3', minAge: 18, state: 'published', words: 62.4, hoursAgo: 74,
    cover: ['#E4EDF1', '#223039'], tags: ['深海', '科考', '声呐'],
    summary: '科考船在万米海沟收到一段不属于任何已知声源的信号。',
    intro: '科考船第三次下潜时，声呐记录里多出一段节奏规整的回声。船上的人先是怀疑设备，随后怀疑彼此，最后才肯承认那声音来自比海沟更深的地方。随船记录员把每天的天气、深度与值班表一并写下，让这个近乎不可思议的发现，始终落在可以核对的日常细节里。',
    sig: '把不确定写清楚，也是一种勇气。', chapterCount: 10, seed: 13,
  },
  {
    id: 'b8', title: '旧梦裁缝铺', author: '苏锦', category: '青春小品',
    band: 'C1', minAge: 14, state: 'removed', words: 12.8, hoursAgo: 98,
    cover: ['#F2E8EA', '#33272A'], tags: ['旧衣', '修补', '校园'],
    summary: '她替人修补旧衣，也替人收好没来得及说完的告别。',
    intro: '裁缝铺开在中学后门斜对面，来的多半是拿校服改裤脚的学生。有人送来磨破的袖口，有人只送来一颗多余的纽扣。店主不多问，只把每件衣服的改动记在一张卡片背面。等到毕业那年的雨季，卡片攒成厚厚一叠，那些没说出口的话才被一针一线地认出来。',
    sig: '线头收好，衣服就能再穿一季。', chapterCount: 8, seed: 4,
  },
  {
    id: 'b9', title: '纸鸢与北风', author: '闻笛', category: '成长散文',
    band: 'C1', minAge: 14, state: 'published', words: 9.4, hoursAgo: 102,
    cover: ['#F1F0E4', '#303228'], tags: ['风筝', '北方', '少年'],
    summary: '一只总也飞不高的纸鸢，陪一个孩子走完转学前的秋天。',
    intro: '北方小城的风从十月起就变得又直又硬。转学前的最后一个秋天，孩子跟着邻家老人学糊纸鸢，竹骨削了三回，尾巴接了五回，仍旧飞不过院墙。老人不急，只教他辨认风向。等到终于放起来的那天，他忽然明白，要告别的其实不是同学，是自己总想抓紧的那点力气。',
    sig: '风不帮忙的时候，就先学会等。', chapterCount: 8, seed: 6,
  },
  {
    id: 'b10', title: '灯塔维修日志', author: '陈屿', category: '行业纪事',
    band: 'C2', minAge: 16, state: 'published', words: 33.7, hoursAgo: 122,
    cover: ['#EFEFE7', '#2C2E28'], tags: ['灯塔', '值班', '检修'],
    summary: '守塔人把三十年的检修记录，写成一部关于耐心的书。',
    intro: '岛上只有一座塔、两间屋和一条受潮的石阶。守塔人每次检修都要填一张表：灯罩擦了几遍、电池组剩下多少、雾笛在哪一刻哑过。三十年攒下来的表格被重新誊抄时，竟显出一条清晰的海岸线——哪年风特别大，哪年船特别少，哪年有人终于没有再来。',
    sig: '灯亮着，就是一句不需要回应的答话。', chapterCount: 10, seed: 8,
  },
  {
    id: 'b11', title: '未寄出的地理课', author: '顾行', category: '旅行札记',
    band: 'C3', minAge: 18, state: 'published', words: 68.2, hoursAgo: 146,
    cover: ['#EAEDE4', '#2A3026'], tags: ['测绘', '山路', '通信'],
    summary: '一位代课教师沿着旧邮路，把没上完的地理课写进信里。',
    intro: '山区教学点撤并那年，代课教师把剩下的半册地理课备课本装进帆布包，沿着旧邮路一个寨子一个寨子地走。她本想把课补完，后来发现要记下的其实是沿途的桥、水位、塌方与每户人家的方向感。信始终没有寄出，却成了那一带最实用的一份非正式地图。',
    sig: '地图画到最后，画的是人怎么来往。', chapterCount: 12, seed: 12,
  },
  {
    id: 'b12', title: '星野晨读', author: '苗小满', category: '校园随笔',
    band: 'C0', minAge: 0, state: 'published', words: 6.2, hoursAgo: 170,
    cover: ['#F5F1E6', '#312D25'], tags: ['晨读', '星空', '同桌'],
    summary: '天没亮的教室里，第一排座位总放着一支借来的铅笔。',
    intro: '冬季晨读开始时，窗外还看得见几颗星。教室里最先亮的是第一排那盏台灯，桌角常躺着一支借来的铅笔，谁都没问过主人。孩子们轮流朗读，声音把玻璃上的白雾一点点烘开。这些细小的清晨后来散进各自的路上，却总在某个需要安静的时刻，被重新想起。',
    sig: '读得慢一点，星星就还在。', chapterCount: 8, seed: 10,
  },
];

function makeBook(seed: BookSeed): Book {
  return {
    id: seed.id,
    title: seed.title,
    author: seed.author,
    category: seed.category,
    band: seed.band,
    minAge: seed.minAge,
    state: seed.state,
    words: seed.words,
    wt: `${seed.words} 万字`,
    updatedAt: isoDaysAgo(Math.floor(seed.hoursAgo / 24)),
    relativeTime: relativeTime(seed.hoursAgo),
    hoursAgo: seed.hoursAgo,
    cover: seed.cover,
    summary: seed.summary,
    intro: seed.intro,
    sig: seed.sig,
    tags: seed.tags,
    chapters: buildChapters(seed.seed, seed.chapterCount),
  };
}

export const BOOKS: Book[] = SEEDS.map(makeBook);

export const DRAFTS: Draft[] = [
  { id: 'd1', title: '未命名·第三章', state: 'EDITABLE', words: 12_400, updatedAt: '2026-11-12' },
  { id: 'd2', title: '山月·番外', state: 'SUBMITTED', words: 3_200, updatedAt: '2026-11-10' },
  { id: 'd3', title: '废弃大纲', state: 'DISCARDED', words: 0, updatedAt: '2026-10-28' },
];

export const REVIEWS: ReviewItem[] = [
  { id: 'r1', title: '霜河渡', author: '北客', kind: '机审', status: 'pending', reason: '疑似敏感词命中（待人工复核）' },
  { id: 'r2', title: '白噪之城', author: '林深', kind: '人审', status: 'passed' },
  { id: 'r3', title: '旧梦裁缝铺', author: '苏锦', kind: '人审', status: 'rejected', reason: '违规内容，已下架' },
];

