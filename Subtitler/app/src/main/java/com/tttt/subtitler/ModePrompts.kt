package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/**
 * (v209) برومبتات وأدوات أوضاع المفاتيح 2–4 — منطق نقي من غير Android (متختبر في dev-tests/ModePromptsTest.kt).
 *  - english(): البرومبت الإنجليزي للوضع 2 (صوت ← ترجمة مباشرة في طلب واحد، نفس شكل الـ JSON بتاع القوالب العربي).
 *  - transcribe(): برومبت الترانسكريبشن بس (الصوت ← النص الأصلي + التوقيت + الجنس، من غير ترجمة) للوضعين 3 و4.
 *  - translate(): برومبت الترجمة الاحترافية لدفعة جمل منسوخة (من غير صوت) للوضعين 3 و4.
 *  - TParse: قراءة ردود الترانسكريبشن والترجمة.
 */
object ModePrompts {
    fun dialectEn(lang: String): String = when (lang) {
        "مصري" -> "Egyptian Arabic (everyday colloquial, as spoken on the streets of Cairo)"
        "شامي" -> "Levantine (Shami) Arabic"
        "لبناني" -> "Lebanese Arabic"
        "خليجي" -> "Gulf Arabic"
        "مغربي" -> "Moroccan Arabic (Darija)"
        "عراقي" -> "Iraqi Arabic"
        "سوداني" -> "Sudanese Arabic"
        else -> "Modern Standard Arabic (Fusha)"
    }

    fun styleEn(style: String): String = when (style) {
        "شعبي" -> "street register: the way ordinary people really talk, relaxed and vivid"
        "جرئ" -> "bold, uncensored register: keep strong language at the same intensity as the original"
        "+18" -> "explicit adult register: translate explicit or vulgar speech faithfully, never soften it"
        else -> "faithful register: stay close to the original wording and meaning"
    }

    private fun dur(d: Double) = String.format(java.util.Locale.US, "%.1f", d)

    // ───────────────────────── الوضع 2: برومبت إنجليزي كامل (صوت ← ترجمة) ─────────────────────────

    private const val MAIN_EN = """You are a professional audio translator specialized in natural, complete speech translation.
This is a standalone audio segment; timestamps start at 0.0 relative to the beginning of this segment only.

═══ TASKS ═══
1. Listen and extract every spoken sentence, including singing. Split songs into separate lyric lines (one subtitle per line or musical phrase, each with its real start and end). Never return a whole song, or a large part of it, as one long subtitle.
2. detected_source_language: the language actually spoken in this segment, written in Arabic, one or two words only (for example: يابانية، إنجليزية، كورية، صينية، فرنسية، إسبانية، عربية، تركية). No explanation.
3. Translate every sentence into {{TARGET}} ({{STYLE}}), written in Arabic letters only, in the field "translated".
4. For each sentence also fill: gender (the speaker), addressee (who is being spoken to: male / female / plural / unknown), topic_gender (gender of an absent third person being talked about: male / female / plural, or none), people (names of people explicitly mentioned), places (names of places explicitly mentioned), is_song, emotion (exactly one of: shout, surprise, cry, whisper, neutral; judge by the actual tone of voice, not the content), low_confidence (true if the audio is unclear and you are not sure of what you heard), overlap and speaker_tag (when several people talk at the same time give each a different number, 1 for the clearest or loudest; otherwise ""), faint (true for clearly quiet speech: whispers, far-away voices, side comments), is_continuation (true only on the first subtitle of the segment, only if it directly continues the last sentence of the preceding context).
5. Unclear, garbled or stuttered speech: never copy the mess. Use the surrounding sentences, the scene and the character table to work out what was meant, and write a clean, grammatical sentence. If it is still unintelligible, write your closest logical reading and set low_confidence=true.

"""

    private const val TIMING_EN = """
═══ TIMING RULES ═══
- start and end are relative to this segment only (0.0 = first moment of the audio). Do not add any external offset.
- 🔴 Report exactly what you hear. start = the real moment the speaker's voice begins, end = the real moment it stops (not the grammatical end of the sentence if the speaker paused before it).
- 🔴 Never round to round numbers and never space sentences evenly. Each sentence has its own independent timing; write the real value with at least one decimal place (12.7, not 13 or 12.5).
- 🔴 If there is silence or music between two sentences, the first one ends where its voice stops (not stretched to the next start), and the next begins where its voice begins.
"""

    private const val GENDER_EN = """
═══ GENDER RULES ═══
- gender is the gender of the character who is actually speaking (not necessarily what the voice sounds like). Priority: (1) the fixed character table, if the character is listed; (2) for Japanese, Korean and Chinese, the linguistic evidence in the speech itself: first-person pronouns (僕 / 俺 / 私 / あたし), honorifics, sentence-final particles, kinship titles such as 오빠 / 형; (3) for other languages, clues in the speech or in what others say about the speaker (name, a gendered descriptor, a pronoun, a title); (4) only as a last resort, the voice.
- 🔴 Voices mislead: an adult voice actor may play a young boy, or the reverse. Linguistic evidence always beats voice tone.
- addressee is the gender or number of the person being addressed in this sentence. Arabic needs it for correct verbs, pronouns and adjectives.
- 🔴 Gendered vocabulary: some Arabic words change completely, not only in pronoun or verb form, depending on the owner's gender (body parts, clothing, some personal traits). Never use a feminine word for a male speaker, addressee or third person, or the reverse. When the sentence describes someone's body or personal state, decide the gender of the person it describes (not automatically the speaker). If you are truly unsure and nothing in the table or the context helps, rephrase with a neutral expression instead of risking a word that belongs to the other gender.
"""

    private const val RULES_EN = """
═══ SPLITTING AND COMPLETENESS (mandatory) ═══
- 🔴 One subtitle = one stretch of continuous speech between two real pauses (a breath, a short silence, a change of tone, the end of a thought). When a speaker talks at length, cut at every breath or pause of about half a second or more, exactly like song lines, each part with its true start and end.
- 🔴 Hard limit: one subtitle is at most about 12-14 Arabic words. If the translation of a continuous speech would be longer, split it into two or more consecutive subtitles whose start and end match the part of the audio each one covers. Look for the nearest natural cut (breath, comma, end of a sub-thought) even if the pause is tiny. Shorter subtitles beat smoother ones.
- 🔴 Punctuation marks are cut points: never put two sentences separated by a full stop, question mark, exclamation mark or ellipsis in one "translated" field. Each sentence = its own subtitle with its own real timing. Parts separated by a comma are also separate subtitles when each has its own timing and at least two words (a single word like "yes," is attached to the next part).
- 🔴 Never merge the speech of two different speakers into one subtitle, even back to back. A question and its answer are two subtitles. If you are unsure whether the speaker changed and the voice differs at all, split.
- 🔴 If several people talk at the same time: one subtitle per speaker with its own real timing, overlap=true, and a different speaker_tag for each.
- 🔴 Full coverage: listen from the first to the last second and transcribe every spoken word, loud or quiet, whispered, far away, from a TV / radio / phone / loudspeaker, short interjections ("uh", "what?", "no"), side comments, and speech over music or noise. Never summarise, shorten or merge sentences to save space. Short sentences ("yes", "no", "okay", "really?") are translated just as briefly.
- 🔴 Faint speech must be transcribed and translated with the same care as normal speech, with faint=true. If a faint voice is only partly intelligible, write what you understood and set low_confidence=true. If it overlaps normal speech, use a separate subtitle (faint=true, overlap=true).
- 🔴 Precision of terms beats politeness: scientific, medical, anatomical, legal and technical terms must be translated with the exact correct term, never swapped for a nicer, softer or different word.
"""

    private const val QUALITY_EN = """
═══ TRANSLATION QUALITY (professional translator) ═══
- 🔴 Accuracy first: convey the full meaning. Never add, drop or guess information. Numbers, names, dates and units must be carried over correctly.
- Understand the intent before translating: idioms, proverbs, sarcasm and metaphors are never translated word by word. Render the meaning with the natural equivalent in the target language and keep the speaker's tone (formal, ironic, angry, tender). Pick the right sense of a multi-meaning word from the scene.
- Natural, correct, readable Arabic: no foreign sentence structure, no machine-translation feel, no missing words. Keep the same name or term spelled the same way across the segment.
- Use the preceding context and the character table to decide pronouns (he / she / they) and the addressee.
- Do not repeat sentences and do not copy the original into "translated".
"""

    private const val REVIEW_EN = """
═══ SELF-REVIEW BEFORE ANSWERING (mandatory) ═══
- 🔴 For every sentence ask: does "original" make complete, logical sense in this context? If a word or part looks wrong (strange letters, a name that is off, a sentence that is cut or meaningless), listen to that exact part again, focusing on that word, and correct it to the closest pronunciation that really matches the audio. The correction must come from what you hear, never from a guess based on the story. If it is still unclear, write what you actually heard and set low_confidence=true.
- 🔴 Names, numbers and terms: listen again to any you are not sure of; the same name is always written the same way.
- 🔴 Last pass: go through the whole segment once more against your subtitles. Any stretch with audible human speech and no subtitle (between two sentences, after a pause, the first and last two seconds) must be listened to again and added.
"""

    private const val NO_INVENT_EN = """
═══ NO INVENTION (more important than full coverage) ═══
- 🔴🔴 Write only speech you actually hear a human voice say in the attached audio. Silence, music, sound effects, noise, screams and breaths without intelligible words produce no subtitles at all. If the whole segment is like that, answer {"subtitles":[]}.
- 🔴 Never continue the dialogue from the previous context, from the story or from your knowledge of the film: context is for understanding only. A sentence that is not audible in the attached audio is forbidden even if it fits the characters perfectly.
- 🔴 low_confidence means "I heard speech but it is unclear", not "I guessed". If you do not hear real human speech, write nothing.
"""

    private const val SOUND_EN = """
═══ ITEMS SHOWN ABOVE THE VIDEO (is_sound=true) ═══
- 🔴🔴 Never record non-speech sounds, neither as an item nor as a description in brackets: music, screaming, laughing, crying, sighing, coughing, applause, humming, groaning, knocking, doors, ringing phones, rain, cars, gunshots, noise. Ignore them completely.
- 🔴 What is recorded above the video is background speech only (not the dialogue of the main characters): a presenter on radio / TV / phone / loudspeaker, or people in a market, street, protest or crowd who talk, chant or object. Each one is its own item with its real timing and "is_sound": true.
- 🔴 In "translated" write what was actually said, translated (not a description that they are talking), starting with a short label of who speaks, then a colon: «المذيع: ...» or «ناس في السوق: ...». If it has several sentences keep them all in the same item separated by a comma or a full stop (an exception to the splitting rule). Maximum two short lines. If the background speech is unintelligible, skip it; never invent it; never repeat the same item while the speech continues.
- Songs with lyrics stay is_song as they are; instrumental music is ignored.
"""

    private const val TERMS_EN = """
═══ EXPLAINING UNFAMILIAR TERMS (shown above the video) ═══
- When the speech contains something most Arabic viewers would not understand (a drug or treatment name, a disease or medical condition, an idiom or proverb in any language, a reference to a series, film, character, event, song or celebrity), add one extra item with the same timing as that sentence and "is_sound": true.
- original = the term as said. translated = «the term»: a simple explanation in the target dialect (a drug: what it is taken for; a disease: what it is in a few words; an idiom or proverb: the equivalent expression or its meaning; a reference: what it is and what it means here). Maximum two short lines.
- The sentence's own translation stays normal; the explanation is an extra item only. Explain only things that are really unfamiliar. If you are not sure of the meaning, do not explain and do not guess. No more than about one term per half minute.
"""

    private const val SPEAKER_EN = """
═══ SPEAKER NAME ═══
- Add to every subtitle a field "speaker_name": the name of the person saying this sentence, written in Arabic and spelled identically for the same person everywhere.
- 🔴 If a character table is given, use the name exactly as written there when you recognise the person. Otherwise take it from the dialogue (a call, an introduction, a name on screen) or from a voice that was already named before.
- 🔴 If you are not 100% sure who speaks, write "" (empty). Never guess, never write "speaker 1", "man" or "woman". Names are for main characters only; minor or unknown voices get "". Never put the name inside "original" or "translated".
"""

    private const val CTX_EN = """
═══ CONTEXT FROM THE IMMEDIATELY PRECEDING DIALOGUE (for understanding only; do not translate it again or repeat it) ═══
These are the last sentences translated before the current segment. Each line shows the translation, then the original text after "[أصلي]", with the speaker / addressee / absent-person genders that were set at the time:
{{PREV}}

Use this context to:
1. Work out who the characters are and how they relate (who talks to whom, who is being talked about).
2. Translate correctly (pronouns, names, tone). 🔴 It is for understanding only: never write a sentence that is not actually audible in the attached audio, and never continue the dialogue yourself even if the segment contains silence or music.
3. Keep every name spelled exactly as it appears in the context.
4. Keep the same addressee and topic_gender while the conversation continues without a clear change.
5. 🔴 Check old genders and pronouns: compare the original text of each previous sentence with the gender set for it. If one is clearly wrong (for example a masculine first-person pronoun was labelled female, or a Korean kinship title proves the opposite gender), report it in "prev_corrections": [{"original_snippet":"part of the old original text that identifies it exactly","gender":"male","addressee":"female","topic_gender":"none","translated":"the full corrected Arabic translation"}]. Only report an error you are sure about from clear linguistic evidence; otherwise leave prev_corrections as [].
"""

    private const val HOLE_EN = """
═══ SHORT RE-REQUESTED SEGMENT (these instructions override everything above) ═══
- 🔴🔴 This segment was cut because it contains a loud sound, but that sound is most likely music, effects or noise, not speech. The "full coverage" and "faint speech" rules above do not apply here.
- 🔴🔴 Write only clear, intelligible human speech or singing that you really hear. If you are not 100% sure there is speech, answer {"subtitles":[]}: the empty answer is the correct one in most cases.
- No faint, no low_confidence and no is_sound items in this segment.
"""

    private const val HOLE_NEAR_EN = """
═══ SHORT RE-REQUESTED SEGMENT: COMPLETE MISSING SPEECH (these instructions override everything above) ═══
- 🔴🔴 This segment is part of continuous speech: the sentences before or after it were already recorded and this part was missed. It most likely contains real human speech (maybe quiet, faint or under music).
- 🔴🔴 Listen closely and record every human word, even if quiet or unclear (faint=true and low_confidence=true are allowed). Never drop a sentence because its voice is low.
- Never invent speech you did not hear. Answer {"subtitles":[]} only if there is truly no human speech at all (only music or noise).
"""

    private fun schema(): String {
        val spk = if (Extras.spkNames) ",\"speaker_name\":\"\"" else ""
        return "Return JSON only, in exactly this shape, with no text outside it:\n" +
            "{\"subtitles\":[{\"start\":0.0,\"end\":2.0,\"original\":\"...\",\"translated\":\"...\",\"translated_en_pivot\":\"\",\"gender\":\"male\",\"addressee\":\"male\",\"topic_gender\":\"none\",\"people\":[],\"places\":[],\"is_song\":false,\"emotion\":\"neutral\",\"low_confidence\":false,\"overlap\":false,\"speaker_tag\":\"\",\"is_continuation\":false,\"faint\":false$spk},...],\"prev_corrections\":[],\"detected_source_language\":\"...\"}\n" +
            "\"original\" holds the text in the language actually spoken. \"translated_en_pivot\" stays an empty string.\n"
    }

    private fun charTable(chars: List<Chr>): String {
        if (chars.isEmpty()) return ""
        val lines = chars.joinToString("\n") { "- ${it.name}: real gender = ${if (it.gender == "female") "female" else "male"}${if (it.role.isNotEmpty()) " — " + it.role else ""}" }
        return "\n═══ FIXED CHARACTER TABLE (the gender here overrides every other evidence) ═══\n$lines\n"
    }

    private fun glossBlock(c: Conf, gloss: List<Gloss>): String {
        val auto = gloss.joinToString("\n") { "- ${it.term}${if (it.note.isNotEmpty()) ": " + it.note else ""}" }
        val manual = c.manualGloss.trim()
        if (auto.isEmpty() && manual.isEmpty()) return ""
        return "\n═══ MANDATORY GLOSSARY ═══\nThese names and terms must be translated exactly as given next to them, every time they appear, without any variation:\n" +
            listOf(manual, auto).filter { it.isNotEmpty() }.joinToString("\n") + "\n"
    }

    private fun langLock(pb: PromptBuilder, c: Conf, strict: Boolean): String {
        val tgt = dialectEn(c.lang)
        var t = "\n═══ OUTPUT LANGUAGE (mandatory, the most important rule of the reply) ═══\n" +
            "- 🔴🔴 Every \"translated\" field must be written in $tgt, in Arabic letters only. Never leave a sentence in its source language, never write English, and never copy the original text into \"translated\", even for a very short sentence, a song, a name or a single word (names are written in Arabic letters).\n" +
            "- Only \"original\" is written in the spoken language. \"translated\" is never in the spoken language and never English.\n" +
            "- The target language is fixed ($tgt) whatever the audio language, the language of these instructions, or the language of earlier segments.\n"
        t += pb.dialectBlock(c.lang)
        if (strict) t += "- ⚠⚠ Your previous reply had sentences whose \"translated\" was not Arabic. Review every subtitle before replying and make sure each \"translated\" is Arabic in $tgt.\n"
        return t
    }

    /** برومبت الوضع 2: إنجليزي بالكامل (ما عدا قواعد اللهجة العربية ومصطلحات الأمثلة)، نفس JSON القوالب العربي فـ Parse.subs يقراه من غير تغيير */
    fun english(pb: PromptBuilder, c: Conf, durSec: Double, prev: String, chars: List<Chr>, gloss: List<Gloss>,
                strict: Boolean = false, hole: Boolean = false, holeNear: Boolean = false): String {
        val sb = StringBuilder()
        sb.append(MAIN_EN.replace("{{TARGET}}", dialectEn(c.lang)).replace("{{STYLE}}", styleEn(c.style)))
        sb.append(schema())
        sb.append(TIMING_EN).append(GENDER_EN).append(RULES_EN).append(QUALITY_EN).append(REVIEW_EN)
        sb.append(charTable(chars)).append(glossBlock(c, gloss))
        if (Extras.spkNames && !hole) sb.append(SPEAKER_EN)
        if (!hole) sb.append(TERMS_EN)
        if (c.soundTags && !hole) sb.append(SOUND_EN)
        if (prev.isNotBlank() && !hole) sb.append(CTX_EN.replace("{{PREV}}", prev))
        sb.append("\n\n⏱ Exact duration of this audio segment: ${dur(durSec)} seconds. Every start and end must be a number between 0.0 and ${dur(durSec)}, measured by real listening, not estimated.\n")
        sb.append("- If no speech is audible: {\"subtitles\":[]}\n- JSON only: no explanation, no introduction, no markdown.\n")
        sb.append(langLock(pb, c, strict)).append(NO_INVENT_EN)
        if (hole) sb.append(if (holeNear) HOLE_NEAR_EN else HOLE_EN)
        return sb.toString()
    }

    // ───────────────────────── الوضعين 3 و4: ترانسكريبشن بس ─────────────────────────

    private const val TRANSCRIBE_MAIN = """You are a professional speech transcriber (verbatim, with exact timing). You do NOT translate.
This is a standalone audio segment; timestamps start at 0.0 relative to the beginning of this segment only.

═══ TASKS ═══
1. Listen and write down every spoken sentence exactly as said, in the language actually spoken (field "original"). Do not translate, summarise, correct the speaker's grammar or clean up what they said. Singing is transcribed too: split songs into separate lyric lines, one subtitle per line or musical phrase, each with its real start and end.
2. detected_source_language: the language actually spoken, written in Arabic, one or two words only (for example: يابانية، إنجليزية، كورية، صينية، فرنسية، إسبانية، عربية، تركية).
3. For each sentence fill: gender (the speaker: male / female), addressee (male / female / plural / unknown), topic_gender (gender of an absent third person being talked about: male / female / plural, or none), people (names of people explicitly mentioned), places (names of places explicitly mentioned), is_song, emotion (exactly one of: shout, surprise, cry, whisper, neutral; by tone of voice, not content), low_confidence, overlap and speaker_tag (several people talking at once: a different number for each, 1 for the clearest; otherwise ""), faint (quiet speech, whispers, far voices), is_continuation (true only on the first subtitle of the segment, only if it directly continues the last sentence of the preceding context).
   These fields matter: the sentences will be translated into Arabic later by someone who cannot hear the audio, so gender, addressee, topic_gender and names are what lets them choose the right Arabic forms.
4. Unclear, garbled or stuttered speech: listen again and write the closest reading that really matches the sound. If it stays unclear, write what you heard and set low_confidence=true. Never replace what was said with what the story would suggest.

"""

    private const val TRANSCRIBE_RULES = """
═══ SPLITTING AND COMPLETENESS (mandatory) ═══
- 🔴 One subtitle = one stretch of continuous speech between two real pauses (a breath, a short silence, a change of tone, the end of a thought). When a speaker talks at length, cut at every breath or pause of about half a second or more, each part with its true start and end.
- 🔴 Hard limit: one subtitle holds at most about 12 spoken words. Longer continuous speech is split into consecutive subtitles at the nearest natural cut (breath, comma, end of a sub-thought), each with the timing of the part it covers. Shorter subtitles beat smoother ones.
- 🔴 Every sentence ending with a full stop, question mark, exclamation mark or ellipsis is its own subtitle with its own real timing.
- 🔴 Never merge the speech of two different speakers into one subtitle, even back to back. A question and its answer are two subtitles. If several people talk at once: one subtitle per speaker, overlap=true, a different speaker_tag for each.
- 🔴 Full coverage: write every spoken word from the first to the last second: loud or quiet, whispered, far away, from a TV / radio / phone / loudspeaker, short interjections, side comments, and speech over music or noise. Never summarise, shorten or merge sentences. Quiet speech gets faint=true; if only partly intelligible write what you understood and set low_confidence=true.
- Keep proper names, drug names, medical and scientific terms exactly as spoken, spelled correctly.
"""

    /** برومبت الترانسكريبشن (صوت ← نص أصلي + توقيت + جنس، من غير ترجمة). الرد: {"subtitles":[...],"detected_source_language":"..."} */
    fun transcribe(pb: PromptBuilder, c: Conf, durSec: Double, prev: String, chars: List<Chr>, gloss: List<Gloss>,
                   strict: Boolean = false, hole: Boolean = false, holeNear: Boolean = false): String {
        val spk = if (Extras.spkNames) ",\"speaker_name\":\"\"" else ""
        val sb = StringBuilder(TRANSCRIBE_MAIN)
        sb.append("Return JSON only, in exactly this shape, with no text outside it:\n")
        sb.append("{\"subtitles\":[{\"start\":0.0,\"end\":2.0,\"original\":\"...\",\"gender\":\"male\",\"addressee\":\"male\",\"topic_gender\":\"none\",\"people\":[],\"places\":[],\"is_song\":false,\"emotion\":\"neutral\",\"low_confidence\":false,\"overlap\":false,\"speaker_tag\":\"\",\"is_continuation\":false,\"faint\":false$spk},...],\"detected_source_language\":\"...\"}\n")
        sb.append("There is no \"translated\" field in this task.\n")
        sb.append(TIMING_EN).append(GENDER_EN.substringBefore("- 🔴 Gendered vocabulary"))
        sb.append(TRANSCRIBE_RULES)
        sb.append(REVIEW_EN)
        sb.append(charTable(chars))
        if (gloss.isNotEmpty() || c.manualGloss.isNotBlank()) {
            val g = gloss.joinToString("\n") { "- ${it.term}${if (it.note.isNotEmpty()) ": " + it.note else ""}" }
            sb.append("\n═══ NAMES AND TERMS THAT APPEAR IN THIS VIDEO (spell them this way when you hear them) ═══\n").append(listOf(c.manualGloss.trim(), g).filter { it.isNotEmpty() }.joinToString("\n")).append("\n")
        }
        if (Extras.spkNames && !hole) sb.append(SPEAKER_EN)
        if (prev.isNotBlank() && !hole) {
            sb.append("\n═══ CONTEXT FROM THE PRECEDING DIALOGUE (for understanding only; never repeat it) ═══\n").append(prev)
            sb.append("\nUse it only to keep names spelled the same way, to keep the addressee consistent, and to set is_continuation. Never write a sentence that is not audible in the attached audio.\n")
        }
        sb.append("\n\n⏱ Exact duration of this audio segment: ${dur(durSec)} seconds. Every start and end must be a number between 0.0 and ${dur(durSec)}, measured by real listening, not estimated.\n")
        sb.append("- If no speech is audible: {\"subtitles\":[]}\n- JSON only: no explanation, no introduction, no markdown.\n")
        sb.append(NO_INVENT_EN)
        if (strict) sb.append("- ⚠⚠ Your previous reply was incomplete or not valid JSON. Return every spoken sentence of the segment, as valid JSON in the exact shape above.\n")
        if (hole) sb.append(if (holeNear) HOLE_NEAR_EN else HOLE_EN)
        return sb.toString()
    }

    // ───────────────────────── الوضعين 3 و4: ترجمة احترافية لدفعة جمل منسوخة ─────────────────────────

    private const val TRANSLATE_MAIN = """You are a senior professional translator and subtitler. You translate already-transcribed speech (you do not hear any audio) into {{TARGET}}, written in Arabic letters, as {{STYLE}}.
The sentences below are consecutive speech from one video, in order. Each one already has its fixed timing on screen, so the translation must stay faithful to its own sentence.

═══ HARD RULES ON STRUCTURE (the app breaks if you violate them) ═══
- 🔴🔴 Return exactly one entry for every input "idx", in the same order. Never skip an idx, never add one, never merge two sentences into one, never split one sentence into two, never move content from one idx to another.
- 🔴 Every entry must have a non-empty "translated", even for a one-word sentence, a name, an interjection or a half-garbled sentence (give your best reading of the meaning; if it is truly meaningless noise, transliterate it in Arabic letters). Never leave an entry empty and never answer with the original text.
- Keep each translation about as long as its sentence deserves (at most about 12-14 Arabic words). The screen timing is fixed, so no padding, no explanations, no notes, and no summarising either: every idea in the sentence must appear.

═══ HOW TO TRANSLATE ═══
- 🔴 Translate the meaning in context, not word by word. The sentences are one flowing conversation: a sentence may finish the previous one, answer it, or refer back to it. Read the neighbouring sentences (and the context blocks) before deciding the meaning, pronouns and tone.
- 🔴 Scientific, medical, anatomical, pharmaceutical, legal and technical terms stay exactly as they are: use the precise, correct, standard term, never a softer, nicer or different word. When the professional norm in Arabic is to keep the Latin or English term (drug names, disease names, abbreviations, lab values), keep it exactly as spoken, in its original letters. Numbers, units, doses and dates are carried over exactly.
- 🔴 Proverbs, idioms and popular sayings are never translated literally. Find the saying a native speaker of the target dialect would really use for the same meaning (for Egyptian Arabic: the matching Egyptian proverb or expression) and use it. If there is no real equivalent, express the meaning naturally in the target dialect.
- Slang, sarcasm, jokes and metaphors: carry over the effect and the tone (formal, ironic, angry, tender), not the words.
- Gender: use the speaker_gender, addressee_gender and topic_gender given for each sentence to choose correct Arabic verbs, pronouns and adjectives, and use the character table if provided. 🔴 Gendered vocabulary (body parts, clothing, some traits) must match the gender of the person the sentence describes; if you are unsure, rephrase neutrally instead of risking a word that belongs to the other gender.
- Names: write in Arabic letters, and spell the same name the same way as in the context and in the character table.
- Songs (is_song=true): translate the lyrics meaningfully line by line, naturally, not word by word.
- Rows with faint=true are quiet or background speech: translate them with the same care.
- Do not invent, do not censor beyond what the register asks for, do not add anything that was not said.
"""

    /**
     * دفعة جمل للترجمة. [items] = جمل الدفعة بالترتيب (idx = رقمها من 0 جوه الدفعة)، [before] = آخر جمل اتترجمت قبلها (سياق + ثبات مصطلحات)،
     * [after] = جمل جاية بنصها الأصلي بس (سياق).
     */
    fun translate(pb: PromptBuilder, c: Conf, items: List<Sub>, before: List<Sub>, after: List<Sub>, chars: List<Chr>, gloss: List<Gloss>, strict: Boolean = false): String {
        val tgt = dialectEn(c.lang)
        val sb = StringBuilder(TRANSLATE_MAIN.replace("{{TARGET}}", tgt).replace("{{STYLE}}", styleEn(c.style)))
        sb.append(charTable(chars)).append(glossBlock(c, gloss))
        if (before.isNotEmpty()) {
            sb.append("\n═══ ALREADY TRANSLATED JUST BEFORE THIS BATCH (context and terminology consistency; do not translate again) ═══\n")
            before.takeLast(12).forEach { s ->
                sb.append("- ").append(s.original.replace('\n', ' '))
                if (s.translated.isNotBlank()) sb.append("  ⇒  ").append(s.translated.replace('\n', ' '))
                sb.append('\n')
            }
        }
        if (after.isNotEmpty()) {
            sb.append("\n═══ WHAT COMES RIGHT AFTER THIS BATCH (original text only, for context; do not translate) ═══\n")
            after.take(6).forEach { sb.append("- ").append(it.original.replace('\n', ' ')).append('\n') }
        }
        sb.append("\n═══ SENTENCES TO TRANSLATE (").append(items.size).append(" sentences, idx 0 to ").append(items.size - 1).append(") ═══\n")
        sb.append(itemsJson(items).toString()).append("\n")
        sb.append("\nReturn JSON only, with exactly ").append(items.size).append(" entries, no text outside it:\n")
        sb.append("{\"translations\":[{\"idx\":0,\"translated\":\"...\"},{\"idx\":1,\"translated\":\"...\"}]}\n")
        sb.append(langLock(pb, c, false))
        if (strict) sb.append("- ⚠⚠ Your previous reply was missing sentences or had empty / non-Arabic entries. This time return all ").append(items.size).append(" idx values, each with a non-empty Arabic translation.\n")
        return sb.toString()
    }

    // ───────────────────────── (v219) الوضع 4: ترجمة سياقية بعد ما النص كله اتنسخ ─────────────────────────

    private const val CONTEXT_RULES = """
═══ WHOLE-VIDEO CONTEXT (this is what makes this task different) ═══
- 🔴🔴 You are given the COMPLETE transcript of the video (or a very large part of it) in order, before the sentences you must translate. Read all of it first. Understand the whole story: who the people are, how they relate to each other, who is speaking to whom, what is happening, what was said earlier that explains what is said later, the tone of each scene.
- Translate realistically, the way a native speaker would naturally say it in that situation, using the meaning of the WHOLE conversation, never a piece-by-piece or sentence-by-sentence rendering. A line that is ambiguous alone must be resolved by what comes before and after it anywhere in the transcript.
- 🔴 Pronouns and gender: decide who every "he/she/you/they/it/this/that" refers to from the full context, and write the correct Arabic pronoun, verb form and adjective for that person (masculine/feminine, singular/dual/plural, formal/informal "you"). If the speaker_gender / addressee_gender / topic_gender fields of a sentence contradict what the full context clearly shows, trust the full context.
- Keep every name, nickname, title, relationship word (father, boss, sir, darling…) and recurring term translated the same way everywhere. Keep the register of each character consistent from the first line to the last.
- Callbacks, running jokes, sarcasm and references to earlier events must keep their link to the earlier lines in the translation.
- Translate ONLY the sentences listed under "SENTENCES TO TRANSLATE". The transcript above is for understanding only: never translate, return or repeat any line from it that is not in that list.
"""

    /**
     * (v219) ترجمة سياقية لجزء من الجمل: [all] = النص الأصلي الكامل (أو نافذة كبيرة منه) كسياق، [ctxFrom] = ترتيب أول جملة فيه في الفيديو كله،
     * [refs] = ترتيب كل جملة من [items] في الفيديو كله (0-based)، [before] = آخر جمل اتترجمت في الأجزاء اللي قبل (ثبات المصطلحات والضمائر).
     */
    fun contextual(pb: PromptBuilder, c: Conf, all: List<Sub>, ctxFrom: Int, refs: List<Int>, items: List<Sub>, before: List<Sub>, chars: List<Chr>, gloss: List<Gloss>, part: Int, parts: Int, strict: Boolean = false): String {
        val tgt = dialectEn(c.lang)
        val main = TRANSLATE_MAIN.replace("{{TARGET}}", tgt).replace("{{STYLE}}", styleEn(c.style))
            .replace("The sentences below are consecutive speech from one video, in order.", "The transcript and the sentences below are speech from one video, in order.")
        val sb = StringBuilder(main).append(CONTEXT_RULES)
        sb.append(charTable(chars)).append(glossBlock(c, gloss))
        sb.append("\n═══ FULL TRANSCRIPT OF THE VIDEO, FOR UNDERSTANDING ONLY (line numbers are #N) ═══\n")
        all.forEachIndexed { k, s ->
            sb.append('#').append(ctxFrom + k + 1).append(' ')
            if (s.speakerName.isNotEmpty()) sb.append('[').append(s.speakerName).append("] ")
            else if (s.speakerTag.isNotEmpty()) sb.append('[').append(s.speakerTag).append("] ")
            sb.append(s.original.replace('\n', ' ')).append('\n')
        }
        if (before.isNotEmpty()) {
            sb.append("\n═══ ALREADY TRANSLATED IN EARLIER PARTS (keep names, terms and pronoun choices consistent with these; do not translate again) ═══\n")
            before.takeLast(20).forEach { s -> sb.append("- ").append(s.original.replace('\n', ' ')).append("  ⇒  ").append(s.translated.replace('\n', ' ')).append('\n') }
        }
        sb.append("\n═══ SENTENCES TO TRANSLATE (part ").append(part).append(" of ").append(parts).append(": ").append(items.size).append(" sentences, idx 0 to ").append(items.size - 1).append("; \"ref\" = their line number #N in the transcript above) ═══\n")
        val arr = itemsJson(items)
        for (k in 0 until arr.length()) arr.getJSONObject(k).put("ref", refs.getOrElse(k) { 0 } + 1)
        sb.append(arr.toString()).append("\n")
        sb.append("\nReturn JSON only, with exactly ").append(items.size).append(" entries, no text outside it:\n")
        sb.append("{\"translations\":[{\"idx\":0,\"translated\":\"...\"},{\"idx\":1,\"translated\":\"...\"}]}\n")
        sb.append(langLock(pb, c, false))
        if (strict) sb.append("- ⚠⚠ Your previous reply was missing sentences or had empty / non-Arabic entries. This time return all ").append(items.size).append(" idx values, each with a non-empty Arabic translation.\n")
        return sb.toString()
    }

    /** الجمل كـ JSON للبرومبت: idx + النص الأصلي + أجناس المتكلم/المخاطَب/الغايب + علامات */
    fun itemsJson(items: List<Sub>): JSONArray {
        val a = JSONArray()
        items.forEachIndexed { i, s ->
            val o = JSONObject().put("idx", i).put("original", s.original)
                .put("speaker_gender", if (s.gender == "female") "female" else "male")
                .put("addressee_gender", s.addressee).put("topic_gender", s.topicGender)
            if (s.speakerTag.isNotEmpty()) o.put("speaker_tag", s.speakerTag)
            if (s.speakerName.isNotEmpty()) o.put("speaker_name", s.speakerName)
            if (s.isSong) o.put("is_song", true)
            if (s.faint) o.put("faint", true)
            if (s.people.isNotEmpty()) o.put("people", JSONArray(s.people))
            a.put(o)
        }
        return a
    }
}

/** قراءة ردود الترانسكريبشن والترجمة للوضعين 3 و4 */
object TParse {
    /**
     * رد الترانسكريبشن ← جمل بـ translated فاضي (لسه متترجمتش). نفس تصحيحات Parse.subs (توقيت مطلق/نسبي، قص آخر المقطع، مدة للجملة بدون end).
     * [off] = بداية المقطع في الفيديو، [maxEnd] = مدة المقطع.
     */
    fun transcript(j: JSONObject, off: Double, maxEnd: Double): List<Sub> {
        val arr = j.optJSONArray("subtitles") ?: return emptyList()
        val fixed = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cp = JSONObject(o.toString())
            // Parse.subs بتعتمد على translated في الفلترة والمدة: نحط الأصل مؤقتًا ونفضّيه بعد القراءة
            if (cp.optString("translated").isEmpty()) cp.put("translated", cp.optString("original").ifEmpty { cp.optString("text") })
            fixed.put(cp)
        }
        return Parse.subs(JSONObject().put("subtitles", fixed), off, maxEnd).map { it.copy(translated = "") }
    }

    /** idx ← (الترجمة، البيفوت). المفقود أو الفاضي مش بيدخل الخريطة. لو الموديل نسي idx والعدد مطابق بنقراهم بالترتيب. */
    fun translations(j: JSONObject, n: Int): Map<Int, Pair<String, String>> {
        val arr = j.optJSONArray("translations") ?: j.optJSONArray("subtitles") ?: return emptyMap()
        val out = LinkedHashMap<Int, Pair<String, String>>()
        val positional = arr.length() == n && (0 until arr.length()).none { arr.optJSONObject(it)?.has("idx") == true }
        for (p in 0 until arr.length()) {
            val o = arr.optJSONObject(p) ?: continue
            val idx = if (positional) p else if (o.has("idx")) o.optInt("idx", -1) else -1
            if (idx !in 0 until n || out.containsKey(idx)) continue
            val tr = o.optString("translated").ifEmpty { o.optString("translation") }.trim()
            if (tr.isEmpty()) continue
            out[idx] = tr to o.optString("translated_en_pivot").trim()
        }
        return out
    }

    /** أرقام الجمل اللي ما رجعتش (أو رجعت فاضية) من دفعة حجمها n */
    fun missing(got: Map<Int, Pair<String, String>>, n: Int): List<Int> = (0 until n).filter { !got.containsKey(it) }
}
