import assert from "node:assert/strict";
import { add } from "./src/broken.js";

assert.equal(add(2, 3), 5, "add must add both operands");
console.log("fixture test passed");
