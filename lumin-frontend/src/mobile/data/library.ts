import { BOOKS } from '../../mocks/mock';
import type { Book } from '../../types';
import type { MobileBook, MobileChapter } from '../types';

/**
 * 移动端书目：复用 Web 端已审校的原创夹具，避免出现第二套互相矛盾的作品设定。
 * 正文为本地演示文本；真实环境由章节正文接口按 chapterId 拉取并替换。
 */

const BODY_POOL: string[] = [
  '天色尚早，窗纸先亮了一层。她把昨夜抄好的页码摊在桌上，又用镇纸压住被风掀起的一角。屋外有人推车经过，木轮碾过湿石的声响慢而稳，像在替这段尚未落笔的记述数拍子。',
  '记录簿的边角已经卷起，前任书写人留下的墨色深浅不一。她沿着每一道笔画往下读，忽然发现被略去的那一行并不在末尾，而是藏在两次寻常交接之间——最不起眼的地方，往往最经得起反复翻看。',
  '午后起了风，院里的影子被拉得很长。来人没有进门，只把一只封好的纸袋放在门槛上，说是等潮退了再拆。她点头应下，转身把这件事照原样记进本子，既不添一句猜测，也不省一个日期。',
  '灯芯爆了一下，屋里短暂地更亮。她想起有人说过，把事情写清楚比把事情写漂亮要难得多。于是删去两行形容，只保留时间、地点与在场的人，让纸面重新安静下来，像退潮后露出的那片平沙。',
  '第二日清早，雾还没散。栈桥尽头传来缆绳收紧的动静，值夜的人正按旧例核对一遍数目。她站在稍远处看，没有上前打扰；有些秩序不必被解释，只要每天有人肯照着做一遍，就足以让后来者安心。',
  '旧信被重新折好，收进原先的封套。纸上的折痕比字迹更旧，说明它曾被反复取出又放回。她忽然明白，所谓交代并不是给出一个结论，而是把来龙去脉留到足够完整，让下一个人不必从猜测开始。',
  '雨停之后，石阶上留着一行浅浅的水印。她顺着水印走到拐角，看见昨日那盏没点亮的灯已经被人擦干净。没有留名，也没有附言；这种不声张的照看，恰好是这些年里最不曾间断的一件事。',
  '夜班将尽，本子合上前又添了最后一行：今日无异常。写完她停了片刻，把笔搁在槽里，听远处第一班船鸣笛。潮水照旧涨落，而记录之所以要继续，正是为了让每个平常日子都有可以回看的位置。',
  '巷口的槐树落了一地细碎的花。卖早点的铺子把蒸笼一层层揭开，白气涌到街面上，先把行人的轮廓晕开了。她站在对街等红灯，听见有人隔着白气喊了一声熟人的名字，像把整条街都叫醒。',
  '老人把旧木匣推到桌中间，匣盖上的铜扣已经发暗。里面没有值钱东西，只有一叠按年份扎好的信、几张车票和一枚缺了角的纽扣。他说这些东西留着也没什么用，可每次搬家都先装进箱子。',
  '机关铺的后院堆着拆下来的铜件，大小齿轮按号数排开。少年蹲在地上，把一枚卡住的销子反复装回原位，直到指尖沾满机油。师傅没有催他，只在旁边磨一把旧凿子，偶尔抬眼看看进度。',
  '城里的广播到整点会停三秒。那三秒里，所有喇叭都发出同样的底噪，像一条看不见的河从屋檐上淌过去。录音师把耳机压紧，听见底噪深处藏着一段很轻的旋律，随后又被下一轮播报盖住。',
  '深潜器上浮的时候，舱壁的水珠一齐往下走。记录员把深度、时间与舱内温度抄进表格，字迹因为低温有些发僵。窗外没有光，只有探照灯扫过时翻起的细碎沉积物，像一场下得很慢的雪。',
  '裁缝铺的窗台上摆着一盆薄荷，叶子被来往的人碰得东倒西歪。店主把改好的校服叠成方块，用牛皮纸包住，又拿铅笔在纸角写下取件人的姓。她写字很轻，怕划破纸，也怕把人的名字写重了。',
  '山里的邮路改道以后，旧邮筒还留在岔口。筒身的绿漆掉了大半，投信口却被磨得很亮。她每年经过都往里面看一眼，明知道不会再有信，仍旧会想起那个总把邮票贴倒的人。',
  '北方的风从操场那头过来，把纸鸢的尾巴吹得笔直。孩子牵着线往后退，鞋跟陷进松软的土里。老人站在旁边，只说再等一等，等风把头抬起来。那天他们等到太阳偏西，纸鸢终于高过院墙。',
  '灯塔的灯罩要擦三遍。第一遍去盐雾，第二遍去油痕，第三遍只用干布，顺着同一个方向收尾。守塔人做这套动作时从不说话，仿佛一开口，就会把灯里那点安静的光惊动。',
  '渡口的清晨比县城早半个时辰。船工解开缆绳前，会先看一看水面，再看一看排队的人。那天他多看了一会儿，因为队伍末尾站着一个不该出现在这里的人，穿着一双干净的、没有沾泥的鞋。',
  '教室里的第一排总亮得最早。台灯的光圈落在摊开的课本上，也落在那支借来的铅笔上。孩子们轮流朗读，声音从窗边传到后墙，把玻璃上的白雾一点点烘开。星星还没退，天已经准备亮了。',
  '她把最后一页校样翻过去，发现页脚有一处很小的折痕。那是排版时留下的，不仔细看根本不会注意。她拿尺子压平，又重新看了一遍那段被改过三次的句子，最后决定不再动它。',
  '海图室的门锁有些涩，推开时要往上提一下。屋里常年有一股旧纸和铁尺的味道，桌上摊着不同年份的潮位表。她用指尖沿着一条等深线走，走到某处忽然停住，因为那里多了一笔不该有的铅笔痕。',
  '茶凉到可以入口的时候，窗外的雨也小了。对面的人把来意说得很慢，像是怕说快了就会反悔。她没有急着回答，只把杯沿转了一圈，看见杯底沉着几片没有舒展开的茶叶。',
  '更鼓敲过三遍，巷子里只剩打更人自己的脚步声。他照例在每个拐角停一停，听一听身后的动静。今夜没有异样，只有一只猫从墙头跳下来，把一片瓦踩得轻响。他记下时间，继续往前走。',
];

const TITLES: Record<string, string[]> = {
  b1: [
    '潮水退去之后',
    '值班簿的第一页',
    '灯塔与旧信',
    '堤上的第三盏灯',
    '雾里的航标',
    '盐田的黄昏',
    '封冻之前',
    '迟到的船',
    '纸袋上的名字',
    '父亲的航线',
    '第三盏灯亮了',
    '潮汐照旧',
  ],
  b2: ['山月照旧', '木匣里的信', '桂花与归期', '旧邮筒', '四时农事', '窗前的月'],
  b3: ['城门开启时', '铜制齿轮', '机关铺的学徒', '雨夜的更鼓', '纸上的城池', '匠人名录'],
  b4: ['信号中断的第七天', '无声频段', '记忆保管所', '白噪里的名字', '旧磁带', '最后一频道'],
  b5: ['第一笼蒸汽', '巷口的槐树', '雨棚下的座位', '冬夜的汤锅', '第二封未寄出的信', '拆迁之前'],
  b9: ['纸鸢飞过北墙', '风从操场来', '地理课的下半节', '未寄出的明信片', '山道上的邮筒', '星野下的课桌'],
};

const EDITOR_NOTES: Record<string, string> = {
  b1: '一部把潮汐写进人事的纪事小说。文字克制，细节却极密，适合在夜里慢读。',
  b2: '山乡四时与一封迟到二十年的家书。没有激烈转折，读完却会想起某个久未联系的人。',
  b3: '机关术、匠籍与皇城棋局。设定扎实，群像鲜活，是本站今年最受关注的东方奇想。',
  b4: '当记忆可以被交易，城市开始失语。近未来外壳下，写的仍是个体如何保存自己。',
  b5: '一条老巷、一家早餐铺。烟火气不是背景，而是人物彼此照看的办法。',
  b9: '一只总也飞不高的纸鸢，陪一个孩子走完转学前的秋天。轻，但不薄。',
};

const STATS: Record<string, MobileBook['stats']> = {
  b1: { reads: '128.4 万', favorites: '3.2 万', recommend: 96, revenue: '¥ 4,286' },
  b2: { reads: '76.9 万', favorites: '1.8 万', recommend: 93, revenue: '¥ 2,140' },
  b3: { reads: '214.6 万', favorites: '6.7 万', recommend: 98, revenue: '¥ 9,830' },
  b4: { reads: '58.2 万', favorites: '1.4 万', recommend: 91, revenue: '¥ 1,760' },
  b5: { reads: '43.8 万', favorites: '0.9 万', recommend: 89, revenue: '¥ 980' },
  b9: { reads: '31.2 万', favorites: '0.7 万', recommend: 88, revenue: '¥ 620' },
};

function buildBody(book: Book, chapterIndex: number): string[] {
  const seed = book.id.charCodeAt(1) + chapterIndex * 7;
  const paragraphs: string[] = [];
  if (chapterIndex === 0) {
    paragraphs.push(book.intro);
  }
  for (let i = 0; i < 4; i += 1) {
    paragraphs.push(BODY_POOL[(seed + i * 3) % BODY_POOL.length]);
  }
  if (chapterIndex > 0) {
    paragraphs.splice(2, 0, BODY_POOL[(seed + 11) % BODY_POOL.length]);
  }
  return paragraphs;
}

function toMobileBook(book: Book, index: number): MobileBook {
  const titles = TITLES[book.id] ?? book.chapters.map((chapter) => chapter.title);
  const chapters: MobileChapter[] = titles.map((title, chapterIndex) => {
    const source = book.chapters[chapterIndex] ?? book.chapters[0];
    return {
      ...source,
      id: `${book.id}-c${chapterIndex + 1}`,
      no: chapterIndex + 1,
      title,
      body: buildBody(book, chapterIndex),
    };
  });

  return {
    ...book,
    chapters,
    editorNote: EDITOR_NOTES[book.id] ?? book.summary,
    featured: index === 0,
    editorPick: index < 4,
    stats: STATS[book.id] ?? { reads: '12.6 万', favorites: '0.3 万', recommend: 86, revenue: '¥ 320' },
  };
}

const ORDER = ['b1', 'b2', 'b3', 'b5', 'b4', 'b9'];

export const MOBILE_BOOKS: MobileBook[] = ORDER.map((id) => {
  const source = BOOKS.find((book) => book.id === id);
  if (!source) {
    throw new Error(`缺少移动端书目夹具：${id}`);
  }
  return toMobileBook(source, ORDER.indexOf(id));
});

export const FEATURED_BOOK = MOBILE_BOOKS[0];

export function findBook(bookId: string): MobileBook | undefined {
  return MOBILE_BOOKS.find((book) => book.id === bookId);
}

export function formatCount(value: string): string {
  return value.replace(' 万', '万');
}
