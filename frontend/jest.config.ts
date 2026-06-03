import type { Config } from "jest";

const config: Config = {
  testEnvironment: "jsdom",
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
  transform: {
    "^.+\\.(ts|tsx)$": ["ts-jest", { tsconfig: { jsx: "react-jsx" } }],
  },
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/$1",
    // stub CSS imports — Next.js globals.css is not needed in unit tests
    "\\.(css|scss)$": "<rootDir>/__mocks__/styleMock.ts",
  },
  testMatch: ["**/__tests__/**/*.{ts,tsx}", "**/*.{test,spec}.{ts,tsx}"],
  collectCoverageFrom: [
    "components/**/*.{ts,tsx}",
    "lib/**/*.{ts,tsx}",
    "!**/*.d.ts",
  ],
};

export default config;
