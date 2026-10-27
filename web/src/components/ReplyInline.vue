<!-- 安全的行内加粗和代码；不使用v-html，模型输出不能构造DOM或加载远程资源。 -->
<script setup lang="ts">
import { computed } from "vue";
import { replyParts } from "../workspace/reply";
/** 仅为展示文本，不能作为指令执行。 */
const props = defineProps<{ text: string }>();
const parts = computed(() => replyParts(props.text));
</script>
<template>
  <template v-for="(part, index) in parts" :key="index"
    ><strong v-if="part.kind === 'strong'">{{ part.text }}</strong
    ><code v-else-if="part.kind === 'code'">{{ part.text }}</code
    ><template v-else>{{ part.text }}</template></template
  >
</template>
