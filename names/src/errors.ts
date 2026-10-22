// One error type for everything the API refuses, so the handler can turn it into a JSON reply.

export class ApiError extends Error {
  status: number;
  code: string;
  /** Extra headers for the reply, such as Retry-After on a rate limit. */
  headers: Record<string, string>;
  constructor(status: number, code: string, message: string, headers: Record<string, string> = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
}
