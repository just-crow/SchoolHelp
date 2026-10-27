// The image processor requires images; plain text must go straight to the tokenizer.
export function prepareVisionInputs(processor,text,images){
 return images.length?processor(text,images):processor.tokenizer(text);
}
